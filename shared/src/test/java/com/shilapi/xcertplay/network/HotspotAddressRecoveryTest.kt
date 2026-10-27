package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotAddressRecoveryTest {
    private var now = 0L
    private val logs = mutableListOf<String>()

    private fun recovery(sampled: () -> List<InetAddress>) = HotspotAddressRecovery(
        sample = sampled,
        cancelled = { false },
        pause = { millis -> now += millis },
        nowMillis = { now },
        log = { logs.add(it) },
    )

    private fun ipv4(text: String): InetAddress = InetAddress.getByName(text)

    private fun scopedIpv6(): InetAddress {
        val bytes = ByteArray(16)
        bytes[0] = 0xfe.toByte()
        bytes[1] = 0x80.toByte()
        bytes[15] = 1
        return Inet6Address.getByAddress("wlan0", bytes, 1)
    }

    @Test
    fun usableIpv4CapsTheIpv6WaitAtTheGraceInsteadOfTheFullTimeout() {
        val ipv4 = ipv4("192.168.49.1")
        val result = recovery { listOf(ipv4) }
            .awaitCandidates(listOf(ipv4), HotspotAddressPolicy.FAMILY_IPV6)

        assertEquals(listOf(ipv4), result)
        assertEquals(2_000L, now)
        assertTrue(logs.any { it.contains("grace expired") })
    }

    @Test
    fun noUsableAddressStillWaitsTheFullTimeout() {
        val result = recovery { emptyList() }
            .awaitCandidates(emptyList(), HotspotAddressPolicy.FAMILY_IPV6)

        assertEquals(emptyList<InetAddress>(), result)
        assertEquals(15_000L, now)
        assertTrue(logs.any { it.contains("unavailable") })
    }

    @Test
    fun ipv6AppearingInsideTheGraceStillWins() {
        val ipv4 = ipv4("192.168.49.1")
        val ipv6 = scopedIpv6()
        var calls = 0
        val result = recovery {
            calls++
            if (calls >= 2) listOf(ipv4, ipv6) else listOf(ipv4)
        }.awaitCandidates(listOf(ipv4), HotspotAddressPolicy.FAMILY_IPV6)

        assertTrue(result.contains(ipv6))
        assertTrue(logs.any { it.contains("ready") })
    }
}
