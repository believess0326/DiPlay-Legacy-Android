package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothAdapter

/**
 * Best effort reset for legacy or rooted head units (e.g. Honda Connect 1 / Tegra 3 Broadcom BT):
 * toggling Wi-Fi or switching modes often leaves their Bluetooth daemon in a wedged state where
 * RFCOMM discovery fails with "Service discovery failed" or "Device or resource busy".
 * Calling `svc bluetooth disable` then `svc bluetooth enable` recovers the stack.
 */
object BluetoothStackReset {
    fun reset(
        tag: String = "bluetooth",
        isCancelled: () -> Boolean = { false },
        onDiagnostic: (String) -> Unit = {},
    ) {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: Exception) {
            null
        } catch (_: Error) {
            null
        } ?: run {
            onDiagnostic("$tag: bluetooth reset skipped (no adapter)")
            return
        }
        if (!adapter.isEnabled) {
            onDiagnostic("$tag: bluetooth reset skipped (adapter off)")
            return
        }
        var disabled = RootShell.run("svc bluetooth disable", 10_000).success
        if (!disabled) {
            disabled = runCatching { adapter.disable() }.getOrDefault(false)
        }
        if (!disabled) {
            onDiagnostic("$tag: bluetooth reset unavailable (neither root nor adapter.disable succeeded)")
            return
        }
        try {
            val offDeadline = System.nanoTime() + 5_000_000_000L
            while (adapter.state != BluetoothAdapter.STATE_OFF && System.nanoTime() < offDeadline) {
                if (isCancelled()) break
                Thread.sleep(200)
            }
            if (adapter.state != BluetoothAdapter.STATE_OFF) {
                onDiagnostic("$tag: bluetooth reset: adapter did not turn off in time; switching it back on")
            }
        } finally {
            val enabled = runCatching { RootShell.run("svc bluetooth enable", 10_000).success }
                .getOrDefault(false)
            if (!enabled) {
                runCatching { adapter.enable() }
            }
        }
        val onDeadline = System.nanoTime() + 10_000_000_000L
        while (!adapter.isEnabled && System.nanoTime() < onDeadline) {
            if (isCancelled()) return
            Thread.sleep(200)
        }
        onDiagnostic(
            if (adapter.isEnabled) "$tag: bluetooth stack reset done" else
                "$tag: bluetooth stack reset done (adapter still coming up)",
        )
    }
}
