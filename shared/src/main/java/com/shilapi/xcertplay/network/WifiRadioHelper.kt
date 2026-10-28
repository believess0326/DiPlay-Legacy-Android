package com.shilapi.xcertplay.network

import android.content.Context

/**
 * Runs [WifiRadioHelperMain] as root through [RootShell] (issue #301(a)).
 * Only invoked by HostapdHotspotManager after `svc wifi enable` left wlan0
 * absent: on some firmware the `svc` binary is a hollow stub, and the helper
 * enables the radio through the framework API directly instead. The helper
 * only switches the radio on — hostapd still owns the AP itself, exactly as
 * in the normal path.
 */
object WifiRadioHelper {
    internal const val HEADER = "DIPLAY_WIFI_RADIO_V1"

    data class Result(val ok: Boolean, val raw: String)

    fun run(context: Context): Result {
        val quoted = "'" + context.packageCodePath.replace("'", "'\"'\"'") + "'"
        val cmd = "CLASSPATH=$quoted app_process /system/bin " +
            "${WifiRadioHelperMain::class.java.name} ${context.packageName} enable"
        val output = RootShell.run(cmd, 30_000).output
        return Result(ok = parse(output), raw = output.trim().replace('\n', ' ').take(200))
    }

    internal fun parse(output: String?): Boolean =
        output?.lines()?.any { it.trim() == "$HEADER|OK" } ?: false
}
