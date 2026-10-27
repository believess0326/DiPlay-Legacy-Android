package com.shilapi.xcertplay.network

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BluetoothStackResetTest {

    @Test
    fun resetSkipsGracefullyWhenNoAdapterOrAdapterOff() {
        val diagnostics = mutableListOf<String>()
        BluetoothStackReset.reset(
            tag = "test-reset",
            isCancelled = { false },
            onDiagnostic = { diagnostics.add(it) },
        )
        // In Robolectric or host environment, it logs skip or unavailability cleanly without crashing
        assertTrue(diagnostics.isNotEmpty())
    }

    @Test
    fun resetHonorsCancellation() {
        val diagnostics = mutableListOf<String>()
        BluetoothStackReset.reset(
            tag = "test-cancel",
            isCancelled = { true },
            onDiagnostic = { diagnostics.add(it) },
        )
        assertTrue(diagnostics.isNotEmpty())
    }
}
