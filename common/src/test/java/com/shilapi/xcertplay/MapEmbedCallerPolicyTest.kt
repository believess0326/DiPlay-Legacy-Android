package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapEmbedCallerPolicyTest {
    private val stranger = MapEmbedCallerPolicy.UNKNOWN

    @Test fun anAppWithNoStandingIsRefusedWhetherOrNotThisBuildIsDebuggable() {
        assertFalse(MapEmbedCallerPolicy.isAllowed(stranger, thisAppIsDebuggable = false))
        assertFalse(MapEmbedCallerPolicy.isAllowed(stranger, thisAppIsDebuggable = true))
    }

    @Test fun eachRecognisedKindOfCallerIsAccepted() {
        assertTrue(MapEmbedCallerPolicy.isAllowed(stranger.copy(isThisApp = true), false))
        assertTrue(MapEmbedCallerPolicy.isAllowed(stranger.copy(isSystemUid = true), false))
        assertTrue(MapEmbedCallerPolicy.isAllowed(stranger.copy(sharesOurSignature = true), false))
        assertTrue(MapEmbedCallerPolicy.isAllowed(stranger.copy(isLauncher = true), false))
    }

    @Test fun debuggablePeersCountOnlyWhenThisBuildIsDebuggable() {
        val peer = stranger.copy(isDebuggablePeer = true)
        assertFalse(MapEmbedCallerPolicy.isAllowed(peer, thisAppIsDebuggable = false))
        assertTrue(MapEmbedCallerPolicy.isAllowed(peer, thisAppIsDebuggable = true))
    }

    @Test fun systemUidDetectionFollowsTheApplicationUidRange() {
        listOf(0, 1000, 2000, 9999, 101000).forEach { assertTrue("uid $it", MapEmbedCallerPolicy.isSystemUid(it)) }
        // 10000 is the first application uid, on every Android user.
        listOf(10000, 10123, 110000, 110123).forEach { assertFalse("uid $it", MapEmbedCallerPolicy.isSystemUid(it)) }
        // A message that did not arrive through binder has no caller to vouch for.
        assertFalse(MapEmbedCallerPolicy.isSystemUid(-1))
    }
}
