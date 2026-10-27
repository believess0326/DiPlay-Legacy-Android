package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessLinkFailurePromptTest {
    @Test fun firstThreeFailuresDoNotShow() {
        val prompt = WirelessLinkFailurePrompt()
        assertFalse(prompt.onFailure())
        assertFalse(prompt.onFailure())
        assertFalse(prompt.onFailure())
        assertEquals(3, prompt.consecutiveFailures)
    }

    @Test fun fourthFailureShows() {
        val prompt = WirelessLinkFailurePrompt()
        repeat(3) { prompt.onFailure() }
        assertTrue(prompt.onFailure())
        assertEquals(4, prompt.consecutiveFailures)
        assertTrue(prompt.promptShown)
    }

    @Test fun furtherFailuresInSameStreakDoNotReshow() {
        val prompt = WirelessLinkFailurePrompt()
        repeat(4) { prompt.onFailure() }
        assertFalse(prompt.onFailure())
        assertFalse(prompt.onFailure())
        assertEquals(6, prompt.consecutiveFailures)
    }

    @Test fun sessionActivatedResetsStreakSoPromptCanShowAgain() {
        val prompt = WirelessLinkFailurePrompt()
        repeat(4) { prompt.onFailure() }
        prompt.onSessionActivated()
        assertEquals(0, prompt.consecutiveFailures)
        assertFalse(prompt.promptShown)
        repeat(3) { assertFalse(prompt.onFailure()) }
        assertTrue(prompt.onFailure())
    }

    @Test fun restoredCountThreeShowsOnNextFailure() {
        val prompt = WirelessLinkFailurePrompt(consecutiveFailures = 3, promptShown = false)
        assertTrue(prompt.onFailure())
    }

    @Test fun restoredCountAtThresholdWithoutShownFlagShowsOnNextFailure() {
        val prompt = WirelessLinkFailurePrompt(consecutiveFailures = 4, promptShown = false)
        assertTrue(prompt.onFailure())
    }

    @Test fun restoredShownStreakNeverReshows() {
        val prompt = WirelessLinkFailurePrompt(consecutiveFailures = 9, promptShown = true)
        assertFalse(prompt.onFailure())
    }
}
