package com.shilapi.xcertplay.network

import java.io.IOException

object WirelessStartupPolicy {
    const val HOTSPOT_READY_MILLIS = 15_000L
    // Right after a cold boot the system hotspot legitimately does not exist yet:
    // on slow head units the vendor hotspot service can take a minute or more to
    // bring the AP up, so a 15 s observation only burns the startup retry budget
    // and leaves a "hotspot not ready" error on screen for a normal boot wait.
    // Attempts that begin within BOOT_GRACE_MILLIS of boot get the longer
    // readiness budget for both the auto-enable step and the interface
    // observation; anything started later keeps the regular budget.
    const val BOOT_GRACE_MILLIS = 180_000L
    const val BOOT_HOTSPOT_READY_MILLIS = 60_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    const val FIRST_TCP_MILLIS = 30_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L
}

enum class WirelessStartupFailure { HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION }

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)
