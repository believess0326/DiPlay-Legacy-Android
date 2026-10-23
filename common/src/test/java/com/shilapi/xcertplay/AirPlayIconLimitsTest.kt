package com.shilapi.xcertplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AirPlayIconLimitsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun limitsBoundaryValues() {
        // Exactly at the limits is compliant; one past in any dimension is not.
        assertFalse(
            AirPlayPersistence.iconExceedsLimits(
                AirPlayPersistence.ICON_MAX_BYTES,
                AirPlayPersistence.ICON_MAX_EDGE,
                AirPlayPersistence.ICON_MAX_EDGE,
            ),
        )
        assertTrue(AirPlayPersistence.iconExceedsLimits(AirPlayPersistence.ICON_MAX_BYTES + 1, 64, 64))
        assertTrue(AirPlayPersistence.iconExceedsLimits(1024, AirPlayPersistence.ICON_MAX_EDGE + 1, 64))
        assertTrue(AirPlayPersistence.iconExceedsLimits(1024, 64, AirPlayPersistence.ICON_MAX_EDGE + 1))
        assertFalse(AirPlayPersistence.iconExceedsLimits(4096, 192, 192))
    }

    @Test fun scaleLadderDescendsFromTheMaxEdge() {
        val ladder = AirPlayPersistence.iconScaleLadder()
        assertEquals(AirPlayPersistence.ICON_MAX_EDGE, ladder.first())
        assertEquals(ladder.sortedDescending(), ladder)
        assertTrue(ladder.all { it in 192..AirPlayPersistence.ICON_MAX_EDGE })
    }

    @Test fun undecodableIconBytesPassThroughUnchanged() {
        // The load path falls back to the default icon for undecodable data;
        // normalization must not destroy the evidence by altering the bytes.
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertArrayEquals(garbage, AirPlayPersistence.normalizeCustomAirPlayIconBytes(garbage))
    }

    @Test fun savedIconRoundTripsThroughPersistence() {
        val garbage = byteArrayOf(9, 8, 7, 6)
        AirPlayPersistence.saveCustomAirPlayIcon(context, garbage)
        assertArrayEquals(garbage, AirPlayPersistence.loadCustomAirPlayIconBytes(context))
        AirPlayPersistence.clearCustomAirPlayIcon(context)
    }
}
