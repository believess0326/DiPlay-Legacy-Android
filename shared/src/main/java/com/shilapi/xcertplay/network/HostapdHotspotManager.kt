package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Experimental root hotspot backend (issues #294/#299): for head units where
 * no framework path can open an AP (framework Wi-Fi off, no Wi-Fi Direct,
 * LocalOnlyHotspot unavailable), drive the unit's own hostapd and dnsmasq as
 * root, in the arrangement proven on those units:
 *
 *  1. kill stale hostapd/dnsmasq and stale lease state;
 *  2. force the framework Wi-Fi radio on (`svc wifi enable`) so wlan0 exists;
 *  3. generate hostapd.conf from the configured SSID/password/channel, copy
 *     it to the standard /data/misc/wifi/hostapd.conf, and start the system's
 *     /system/bin/hostapd;
 *  4. address wlan0 as 192.168.42.1/24 and start the system dnsmasq (DHCP);
 *  5. reset the Bluetooth stack (best effort) — on the target units the
 *     stack wedges once the AP is up, and the RFCOMM bootstrap that follows
 *     hotspot-ready only succeeds after a reset.
 *
 * Everything runs through [RootShell]; when root or a required system binary
 * is missing, [start] fails with the reason instead of half-starting.
 */
class HostapdHotspotManager(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val channel: Int,
    private val onDiagnostic: (String) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
) : WirelessHotspotManager {
    private val appContext = context.applicationContext

    @Volatile
    private var closed = false
    private var started = false
    private var wifiWasOn = true
    private var confirmedClientCount: Int? = null

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "HostapdHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        HostapdConfig.error(ssid, passphrase, channel)?.let { throw IOException(it) }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        fun remainingMillis(): Long = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        fun checkActive() {
            if (closed || isCancelled()) throw InterruptedIOException("Hostapd hotspot startup cancelled")
            if (System.nanoTime() > deadline) {
                throw IOException("hostapd: startup did not complete within ${timeoutMillis}ms")
            }
        }

        onDiagnostic("hostapd: starting hotspot ssid='$ssid' channel=$channel")
        if (!RootShell.isRootAvailable()) {
            throw IOException(
                "hostapd: root (su) is not available on this head unit; " +
                    "the root hotspot mode needs a rooted unit",
            )
        }
        // Probe for the binaries instead of assuming /system/bin: firmware
        // variants ship them in different directories, and the first field
        // report (issue #301) failed this check on a unit whose binaries
        // exist elsewhere. The raw probe output goes to the diagnostic log
        // either way, so a "missing" verdict carries its own evidence.
        val probe = RootShell.run(HostapdConfig.binaryProbeScript(), 5_000)
        val probeText = probe.output.trim().replace('\n', ' ').take(300)
        onDiagnostic("hostapd: binary probe: $probeText")
        val binaries = HostapdConfig.parseBinaryProbe(probe.output)
        val hostapdPath = binaries["hostapd"]
        val dnsmasqPath = binaries["dnsmasq"]
        if (hostapdPath == null || dnsmasqPath == null) {
            throw IOException(
                "hostapd: hostapd or dnsmasq not found in " +
                    HostapdConfig.BINARY_DIRS.joinToString(" ") + " (probe: $probeText)",
            )
        }

        RootShell.run(HostapdConfig.killStaleScript(), 10_000)
        checkActive()

        wifiWasOn = readFrameworkWifiOn()
        if (!wifiWasOn) {
            onDiagnostic("hostapd: framework Wi-Fi is off; enabling the radio as root")
            RootShell.run("svc wifi enable", 10_000)
            awaitInterface(deadlineMillis(deadline, 15_000)) {
                RootShell.run("ls /sys/class/net/${HostapdConfig.INTERFACE_NAME} 2>/dev/null | wc -l", 3_000)
                    .output.contains("1")
            }
            if (closed || isCancelled()) throw InterruptedIOException("Hostapd hotspot startup cancelled")
            if (!interfaceExists()) {
                val netdevs = RootShell.run("ls /sys/class/net 2>/dev/null", 3_000)
                    .output.trim().replace('\n', ' ').take(200)
                throw IOException(
                    "hostapd: ${HostapdConfig.INTERFACE_NAME} did not appear after enabling Wi-Fi " +
                        "(netdevs: $netdevs)",
                )
            }
        }

        val conf = HostapdConfig.renderConf(ssid, passphrase, channel)
        // Deploy by staging the file in app-private storage and copying it
        // into place as root — the recipe proven on-device (issue #301). The
        // earlier heredoc write depends on the root shell's temp directory,
        // which does not exist on some firmware, and on shell printf quirks;
        // a plain file write plus `cp` avoids both.
        val staged = File(appContext.filesDir, "hostapd_full.conf")
        try {
            staged.writeText(conf)
        } catch (error: Exception) {
            throw IOException("hostapd: hostapd_full.conf deploy failed: could not stage the config: ${error.message}")
        }
        val deployed = RootShell.run(
            "mkdir -p /data/misc/wifi; cp ${staged.absolutePath} ${HostapdConfig.TMP_CONF_PATH}" +
                " && cp ${HostapdConfig.TMP_CONF_PATH} ${HostapdConfig.CONF_PATH}",
            10_000,
        )
        if (!deployed.success) {
            throw IOException("hostapd: hostapd_full.conf deploy failed: ${deployed.output.trim().take(200)}")
        }
        checkActive()

        // Release any half-up state before hostapd claims the interface
        // (the proven on-device recipe does the same).
        RootShell.run("ifconfig ${HostapdConfig.INTERFACE_NAME} down 2>/dev/null", 5_000)
        val launched = RootShell.run(
            "$hostapdPath -B ${HostapdConfig.CONF_PATH} >${HostapdConfig.HOSTAPD_LOG_PATH} 2>&1 & sleep 1; " +
                "ps | grep hostapd | grep -v grep | wc -l",
            15_000,
        )
        if (!launched.success || launched.output.trim().lines().lastOrNull()?.trim() != "1") {
            val log = RootShell.run("cat ${HostapdConfig.HOSTAPD_LOG_PATH} 2>/dev/null | tail -5", 5_000)
            throw IOException(
                "hostapd: hostapd did not stay up; hapd.log=${log.output.trim().replace('\n', ' ').take(300)}",
            )
        }
        onDiagnostic("hostapd: hostapd is up on ${HostapdConfig.INTERFACE_NAME}")

        var addressed = false
        repeat(3) {
            val result = RootShell.run(
                "ifconfig ${HostapdConfig.INTERFACE_NAME} ${HostapdConfig.HOST_ADDRESS} " +
                    "netmask ${HostapdConfig.NETMASK} up; " +
                    "ifconfig ${HostapdConfig.INTERFACE_NAME} | grep 'inet addr:${HostapdConfig.HOST_ADDRESS}' | wc -l",
                10_000,
            )
            if (result.output.trim().lines().lastOrNull()?.trim() == "1") {
                addressed = true
                return@repeat
            }
            Thread.sleep(500)
            checkActive()
        }
        if (!addressed) {
            throw IOException("hostapd: ${HostapdConfig.HOST_ADDRESS} is not assignable on ${HostapdConfig.INTERFACE_NAME}")
        }

        RootShell.run(HostapdConfig.dnsmasqCommand(dnsmasqPath), 10_000)
        started = true
        onDiagnostic(
            "hostapd: hotspot ready iface=${HostapdConfig.INTERFACE_NAME} addr=${HostapdConfig.HOST_ADDRESS}",
        )
        resetBluetoothStack()

        val hostAddress = InetAddress.getByName(HostapdConfig.HOST_ADDRESS)
        val mac = runCatching {
            NetworkInterface.getByName(HostapdConfig.INTERFACE_NAME)?.hardwareAddress
                ?.joinToString(":") { "%02x".format(it) }
        }.getOrNull()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
        return WirelessHotspotInfo(
            ssid = ssid,
            passphrase = passphrase,
            security = Iap2WirelessSecurity.WPA_WPA2,
            channel = channel,
            frequencyMHz = wifiChannelToFrequencyMhz(channel, band = 1),
            bssid = mac,
            interfaceName = HostapdConfig.INTERFACE_NAME,
            hostAddress = hostAddress,
            hostAddresses = listOf(hostAddress),
            bandLabel = "2.4 GHz",
            backend = WirelessHotspotBackend.HOSTAPD,
        )
    }

    override fun validateReady() {
        if (!started || closed) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready")
        }
        val probe = RootShell.run(
            "ps | grep hostapd | grep -v grep | wc -l; " +
                "ifconfig ${HostapdConfig.INTERFACE_NAME} 2>/dev/null | grep 'inet addr:${HostapdConfig.HOST_ADDRESS}' | wc -l",
            5_000,
        )
        val counts = probe.output.trim().lines().map { it.trim() }
        if (counts.getOrNull(0) != "1" || counts.getOrNull(1) != "1") {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY,
                "hostapd or the ${HostapdConfig.INTERFACE_NAME} address went away before publication",
            )
        }
    }

    override fun joinedClientCount(): Int? {
        if (!started || closed) return confirmedClientCount
        val leases = RootShell.run("cat ${HostapdConfig.LEASES_PATH} 2>/dev/null", 5_000)
        if (!leases.success && leases.output.isBlank()) return confirmedClientCount
        val count = HostapdConfig.activeLeases(leases.output, System.currentTimeMillis() / 1_000).size
        confirmedClientCount = count
        return count
    }

    override fun connectionDiagnosticSnapshot(): String {
        val count = joinedClientCount()
        return "hostapd leases=${count ?: "unknown"} iface=${HostapdConfig.INTERFACE_NAME}"
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!started && !wifiWasOn) return
        runCatching {
            RootShell.run(
                HostapdConfig.killStaleScript() + "; ifconfig ${HostapdConfig.INTERFACE_NAME} down 2>/dev/null",
                10_000,
            )
            if (!wifiWasOn) {
                RootShell.run("svc wifi disable", 10_000)
            }
        }
        started = false
    }

    private fun deadlineMillis(deadline: Long, capMillis: Long): Long =
        minOf(capMillis, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1))

    private fun interfaceExists(): Boolean =
        RootShell.run("ls /sys/class/net/${HostapdConfig.INTERFACE_NAME} 2>/dev/null | wc -l", 3_000)
            .output.contains("1")

    private fun awaitInterface(timeoutMillis: Long, probe: () -> Boolean) {
        val end = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < end) {
            if (closed || isCancelled()) return
            if (probe()) return
            Thread.sleep(500)
        }
    }

    private fun readFrameworkWifiOn(): Boolean {
        val result = RootShell.run("settings get global wifi_on", 5_000)
        return result.output.trim().lines().lastOrNull()?.trim() == "1"
    }

    /**
     * Best effort, mirroring the modified builds on the target units: once
     * the AP is up their Bluetooth stack no longer answers, and the RFCOMM
     * bootstrap that follows hotspot-ready needs a fresh stack. Any failure
     * here is logged and the connection attempt continues.
     */
    private fun resetBluetoothStack() {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: Exception) {
            null
        } catch (_: Error) {
            null
        } ?: run {
            onDiagnostic("hostapd: bluetooth reset skipped (no adapter)")
            return
        }
        if (!adapter.isEnabled) {
            onDiagnostic("hostapd: bluetooth reset skipped (adapter off)")
            return
        }
        val disabled = RootShell.run("svc bluetooth disable", 10_000)
        if (!disabled.success) {
            onDiagnostic("hostapd: bluetooth reset unavailable (svc bluetooth disable failed)")
            return
        }
        val offDeadline = System.nanoTime() + 5_000_000_000L
        while (adapter.state != BluetoothAdapter.STATE_OFF && System.nanoTime() < offDeadline) {
            if (closed || isCancelled()) return
            Thread.sleep(200)
        }
        if (adapter.state != BluetoothAdapter.STATE_OFF) {
            onDiagnostic("hostapd: bluetooth reset skipped (adapter did not turn off)")
            return
        }
        RootShell.run("svc bluetooth enable", 10_000)
        val onDeadline = System.nanoTime() + 10_000_000_000L
        while (!adapter.isEnabled && System.nanoTime() < onDeadline) {
            if (closed || isCancelled()) return
            Thread.sleep(200)
        }
        onDiagnostic(
            if (adapter.isEnabled) "hostapd: bluetooth stack reset done" else
                "hostapd: bluetooth stack reset done (adapter still coming up)",
        )
    }
}
