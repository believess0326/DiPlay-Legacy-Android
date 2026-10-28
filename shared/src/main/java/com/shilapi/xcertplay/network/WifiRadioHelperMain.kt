package com.shilapi.xcertplay.network

import android.os.Process
import kotlin.system.exitProcess

/**
 * Privileged Wi-Fi radio helper (issue #301(a)): runs under `app_process` as root
 * (or shell uid) and enables the framework Wi-Fi radio directly through the
 * framework API, instead of going through the `svc` command — which is a hollow
 * stub on some firmware (Civic 4.2.2): it exits 0 but never powers the radio.
 *
 * The caller ([WifiRadioHelper], used by HostapdHotspotManager) only runs this
 * after `svc wifi enable` left wlan0 absent, and only proceeds to hostapd if
 * the interface then appears — so a helper that cannot enable the radio
 * changes nothing about the outcome, it only adds its own evidence line.
 *
 * Prints ONLY the protocol result: no exception text, parcels or credentials.
 */
object WifiRadioHelperMain {
    @JvmStatic fun main(args: Array<String>) {
        // A stuck Binder call must not leave a privileged helper running indefinitely.
        Thread({ Thread.sleep(25_000); exitProcess(2) }, "wifi-radio-helper-deadline")
            .apply { isDaemon = true; start() }
        val ok = try { execute(args) } catch (_: Throwable) { false }
        println("${WifiRadioHelper.HEADER}|${if (ok) "OK" else "FAIL"}")
        exitProcess(0)
    }

    private fun execute(args: Array<String>): Boolean {
        val uid = Process.myUid()
        require(uid == 0 || uid == 2000)
        require(args.size == 2 && args[1] == "enable")
        require(args[0].matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")))
        val wifi = wifiManager() ?: return false
        val iface = Class.forName("android.net.wifi.IWifiManager")
        val setWifiEnabled = iface.getMethod("setWifiEnabled", Boolean::class.javaPrimitiveType)
        val getState = iface.getMethod("getWifiEnabledState")
        // A rejected call returns false; the state change itself is async, so
        // poll for ENABLED (3) before reporting success.
        if (setWifiEnabled.invoke(wifi, true) as? Boolean == false) return false
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            if ((getState.invoke(wifi) as? Int) == 3) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun wifiManager(): Any? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "wifi") ?: return null
        val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
        val asInterface = stub.getMethod("asInterface", Class.forName("android.os.IBinder"))
        return asInterface.invoke(null, binder)
    }
}
