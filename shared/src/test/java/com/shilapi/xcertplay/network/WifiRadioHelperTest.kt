package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRadioHelperTest {
    @Test fun parseAcceptsTheOkProtocolLine() {
        assertTrue(WifiRadioHelper.parse("DIPLAY_WIFI_RADIO_V1|OK"))
    }

    @Test fun parseAcceptsOkAmongHelperNoise() {
        assertTrue(WifiRadioHelper.parse("WARNING: linker\nDIPLAY_WIFI_RADIO_V1|OK\n"))
    }

    @Test fun parseAcceptsOkWithSurroundingWhitespace() {
        assertTrue(WifiRadioHelper.parse("  DIPLAY_WIFI_RADIO_V1|OK  \n"))
    }

    @Test fun parseRejectsFail() {
        assertFalse(WifiRadioHelper.parse("DIPLAY_WIFI_RADIO_V1|FAIL"))
    }

    @Test fun parseRejectsNullAndEmpty() {
        assertFalse(WifiRadioHelper.parse(null))
        assertFalse(WifiRadioHelper.parse(""))
    }

    @Test fun parseRejectsLookalikeLines() {
        assertFalse(WifiRadioHelper.parse("XDIPLAY_WIFI_RADIO_V1|OK"))
        assertFalse(WifiRadioHelper.parse("DIPLAY_WIFI_RADIO_V1|OKAY"))
        assertFalse(WifiRadioHelper.parse("DIPLAY_WIFI_RADIO_V1|OK|extra"))
    }
}
