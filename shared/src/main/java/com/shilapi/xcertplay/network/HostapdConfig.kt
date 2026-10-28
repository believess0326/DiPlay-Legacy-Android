package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/**
 * Pure configuration and parsing logic for the experimental root hostapd
 * hotspot backend (issues #294/#299). Kept free of Android and process APIs
 * so it can be unit-tested directly.
 *
 * The backend drives the head unit's own /system/bin/hostapd and dnsmasq as
 * root on units where no framework path can open an AP at all (framework
 * Wi-Fi off, no Wi-Fi Direct, LocalOnlyHotspot unavailable). The layout
 * mirrors the arrangement proven on those units: wlan0, 192.168.42.1/24,
 * DHCP 192.168.42.10-192.168.42.50, readiness proven by a real lease.
 */
object HostapdConfig {
    const val INTERFACE_NAME = "wlan0"
    const val HOST_ADDRESS = "192.168.42.1"
    const val NETMASK = "255.255.255.0"
    const val DEFAULT_CHANNEL = 1
    const val MIN_CHANNEL = 1
    const val MAX_CHANNEL = 13

    const val CONF_PATH = "/data/misc/wifi/hostapd.conf"
    const val TMP_CONF_PATH = "/data/local/tmp/hostapd_full.conf"
    const val HOSTAPD_LOG_PATH = "/data/local/tmp/hapd.log"
    const val DNSMASQ_LOG_PATH = "/data/local/tmp/dns.log"
    const val LEASES_PATH = "/data/local/tmp/dnsmasq.leases"
    const val DNSMASQ_PID_PATH = "/data/local/tmp/dnsmasq.pid"

    /** Validation error text (English diagnostics), or null when usable. */
    fun error(ssid: String, passphrase: String, channel: Int): String? {
        ManualHotspotValidation.error(ssid, passphrase)?.let { return it.message }
        // The manual-hotspot validation allows an open network; hostapd WPA2
        // mode is the only configuration this backend offers.
        if (passphrase.isEmpty()) return "The hostapd hotspot password must be 8–63 characters"
        // Both values are written verbatim into a config file that hostapd
        // reads as root: one line per directive. A line break would let the
        // value smuggle in further directives, so no control character is
        // accepted (hostapd itself rejects them in wpa_passphrase as well).
        if (hasControlCharacter(ssid) || hasControlCharacter(passphrase)) {
            return "The hostapd hotspot name or password contains a control character"
        }
        // hostapd counts the passphrase in bytes, not characters.
        if (passphrase.encodeToByteArray().size !in 8..63) {
            return "The hostapd hotspot password must be 8–63 bytes"
        }
        if (channel !in MIN_CHANNEL..MAX_CHANNEL) {
            return "hostapd channel must be $MIN_CHANNEL..$MAX_CHANNEL (2.4 GHz)"
        }
        return null
    }

    /** True for ASCII control characters, which includes CR, LF and DEL (hostapd's own test). */
    fun hasControlCharacter(value: String): Boolean =
        value.any { it < ' ' || it == '\u007f' }

    /** The hostapd configuration file body for the generated AP. */
    fun renderConf(ssid: String, passphrase: String, channel: Int): String = buildString {
        append("interface=").append(INTERFACE_NAME).append('\n')
        append("driver=nl80211\n")
        append("ssid=").append(ssid).append('\n')
        append("hw_mode=g\n")
        append("channel=").append(channel).append('\n')
        append("ieee80211n=1\n")
        append("auth_algs=1\n")
        append("wpa=2\n")
        append("wpa_passphrase=").append(passphrase).append('\n')
        append("wpa_key_mgmt=WPA-PSK\n")
        append("rsn_pairwise=CCMP\n")
        append("max_num_sta=8\n")
    }

    /** Directories searched for the hostapd/dnsmasq binaries, in order. */
    val BINARY_DIRS = listOf("/system/bin", "/system/xbin", "/vendor/bin", "/bin")

    /**
     * Shell probe that echoes `hostapd=<path>` / `dnsmasq=<path>` for the
     * first executable found per binary. Most units ship both in
     * /system/bin, but firmware variants move them; a fixed-path existence
     * check reported the backend unusable on a unit whose own modified
     * build drives the same binaries from a different directory
     * (issue #301).
     */
    fun binaryProbeScript(): String =
        "for b in hostapd dnsmasq; do for d in ${BINARY_DIRS.joinToString(" ")}; do " +
            "if [ -x \$d/\$b ]; then echo \"\$b=\$d/\$b\"; break; fi; done; done"

    /** Parses [binaryProbeScript] output into binary name → absolute path. */
    fun parseBinaryProbe(output: String): Map<String, String> =
        output.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eq)
            val path = trimmed.substring(eq + 1).trim()
            if ((name == "hostapd" || name == "dnsmasq") && path.startsWith("/")) {
                name to path
            } else {
                null
            }
        }.toMap()

    /**
     * True when `ifconfig` or `ip addr` output shows [HOST_ADDRESS] on an
     * inet line. Both output dialects occur in the field: classic busybox
     * prints `inet addr:192.168.42.1`, toybox/Android prints
     * `inet 192.168.42.1` — matching only one form misreads a healthy
     * interface as address-less (issue #308).
     */
    fun outputShowsHostAddress(output: String): Boolean =
        output.lineSequence().any { line ->
            line.contains("inet") && line.contains(HOST_ADDRESS)
        }

    /** dnsmasq invocation: DHCP only (DNS port disabled), gateway/DNS = the AP address. */
    fun dnsmasqCommand(dnsmasqPath: String = "/system/bin/dnsmasq"): String =
        "$dnsmasqPath --pid-file=$DNSMASQ_PID_PATH --no-resolv --no-hosts --no-poll " +
            "--port=0 --bind-interfaces --dhcp-lease-max=16 " +
            "--dhcp-leasefile=$LEASES_PATH --interface=$INTERFACE_NAME " +
            "--dhcp-range=192.168.42.10,192.168.42.50,255.255.255.0,12h " +
            "--dhcp-option=3,$HOST_ADDRESS --dhcp-option=6,$HOST_ADDRESS " +
            ">$DNSMASQ_LOG_PATH 2>&1 &"

    /**
     * Kills leftover hostapd/dnsmasq from an earlier run and clears stale lease state.
     *
     * A process is killed only when one of its `ps` fields is exactly the
     * binary name (or a path ending in it). The earlier `grep -E "hostapd"`
     * matched any line that merely contained the word, so an unrelated process
     * (an app package, a helper script, `su` running a hostapd command) could
     * be killed with `kill -9`. The PID is taken from column 2, as on Android's
     * toolbox/toybox `ps`; anything that is not all digits is skipped.
     * `set -f` stops `[kworker/...]`-style names from being glob-expanded.
     */
    fun killStaleScript(): String =
        "set -f; for name in hostapd dnsmasq; do ps | while read line; do set -- \$line; hit=0; " +
            "for f in \"\$@\"; do [ \"\${f##*/}\" = \"\$name\" ] && hit=1; done; " +
            "[ \$hit = 1 ] || continue; case \"\$2\" in ''|*[!0-9]*) continue;; esac; " +
            "kill -9 \$2 2>/dev/null; done; done; sleep 1; " +
            "rm -f $LEASES_PATH $DNSMASQ_PID_PATH"

    /**
     * Removes the generated configuration, which holds the WPA passphrase, plus
     * the logs and lease state of this backend. [TMP_CONF_PATH] sits in the
     * shell-readable /data/local/tmp, so it is also removed as soon as it has
     * been copied to [CONF_PATH].
     */
    fun removeGeneratedFilesScript(): String =
        "rm -f $CONF_PATH $TMP_CONF_PATH $HOSTAPD_LOG_PATH $DNSMASQ_LOG_PATH $LEASES_PATH $DNSMASQ_PID_PATH"

    /** Removes only the shell-readable staging copy of the configuration. */
    fun removeStagedConfScript(): String = "rm -f $TMP_CONF_PATH"

    /**
     * Issue #337: the hostapd launch echoes the daemon's pid (`echo $!`), so
     * a later failure can tell whether THIS hostapd died, was killed, or was
     * never started — instead of only knowing that no hostapd-shaped process
     * matched `ps`. Parses the first all-digits line of the launch shell's
     * merged output; anything else (exec errors, blank lines) is ignored.
     */
    fun parseLaunchedPid(launchOutput: String): Int? =
        launchOutput.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it.all { c -> c.isDigit() } }
            ?.toIntOrNull()

    /**
     * Issue #337: the `ps` line for one pid, if present. Android
     * toolbox/toybox `ps` puts the pid in column 2, busybox in column 1 —
     * accept either, so a missing line means the process is gone, not that
     * the dialect differs. Field equality (not substring) avoids matching
     * pid 123 against pid 1234. Returns the trimmed line, or null.
     */
    fun psLineForPid(psOutput: String, pid: Int): String? {
        val want = pid.toString()
        return psOutput.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .firstOrNull { line ->
                val fields = line.split(Regex("\\s+"))
                fields.getOrNull(0) == want || fields.getOrNull(1) == want
            }
    }

    /**
     * Counts active hostapd processes in `ps` output without depending on `grep`, `wc`, or `tail`.
     * Minimal ROMs (e.g. Android 4.2 toolbox, issues #308, #337) lack these shell utilities.
     */
    fun countHostapdProcesses(psOutput: String): Int =
        psOutput.lineSequence().count { line ->
            val words = line.trim().split(Regex("\\s+"))
            words.any { it == "hostapd" || it.endsWith("/hostapd") } &&
                !words.any { it.contains("grep") }
        }

    /** One dnsmasq lease line: `<expiry epoch> <mac> <ip> <hostname> <client id>`. */
    data class Lease(val expiryEpochSeconds: Long, val macAddress: String, val ipAddress: String)

    fun parseLeases(leasesText: String): List<Lease> =
        leasesText.lineSequence().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 3) return@mapNotNull null
            val expiry = fields[0].toLongOrNull() ?: return@mapNotNull null
            Lease(expiryEpochSeconds = expiry, macAddress = fields[1], ipAddress = fields[2])
        }.toList()

    /** Leases still valid at [nowEpochSeconds]; expiry 0 means infinite in dnsmasq. */
    fun activeLeases(leasesText: String, nowEpochSeconds: Long): List<Lease> =
        parseLeases(leasesText).filter { it.expiryEpochSeconds == 0L || it.expiryEpochSeconds > nowEpochSeconds }
}
