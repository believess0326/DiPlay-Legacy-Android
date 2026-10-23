package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostapdConfigTest {
    @Test fun confCarriesTheConfiguredIdentityAndWpa2Settings() {
        val conf = HostapdConfig.renderConf("My Car", "secret-pass", 6)
        assertTrue(conf.contains("interface=wlan0\n"))
        assertTrue(conf.contains("driver=nl80211\n"))
        assertTrue(conf.contains("ssid=My Car\n"))
        assertTrue(conf.contains("hw_mode=g\n"))
        assertTrue(conf.contains("channel=6\n"))
        assertTrue(conf.contains("wpa=2\n"))
        assertTrue(conf.contains("wpa_passphrase=secret-pass\n"))
        assertTrue(conf.contains("wpa_key_mgmt=WPA-PSK\n"))
        assertTrue(conf.contains("rsn_pairwise=CCMP\n"))
    }

    @Test fun validationRejectsUnusableConfigurations() {
        assertNull(HostapdConfig.error("DiPlay", "12345678", 1))
        assertNull(HostapdConfig.error("DiPlay", "12345678", 13))
        assertNotNull(HostapdConfig.error("", "12345678", 1))
        assertNotNull(HostapdConfig.error("DiPlay", "", 1))
        assertNotNull(HostapdConfig.error("DiPlay", "short", 1))
        assertNotNull(HostapdConfig.error("DiPlay", "12345678", 0))
        assertNotNull(HostapdConfig.error("DiPlay", "12345678", 14))
        assertNotNull(HostapdConfig.error("DiPlay", "12345678", 36))
    }

    @Test fun dnsmasqServesDhcpOnTheApAddressOnly() {
        val command = HostapdConfig.dnsmasqCommand()
        assertTrue(command.startsWith("/system/bin/dnsmasq"))
        assertTrue(command.contains("--port=0"))
        assertTrue(command.contains("--interface=wlan0"))
        assertTrue(command.contains("--dhcp-range=192.168.42.10,192.168.42.50,255.255.255.0,12h"))
        assertTrue(command.contains("--dhcp-leasefile=${HostapdConfig.LEASES_PATH}"))
    }

    @Test fun killStaleScriptTargetsBothDaemonsAndLeaseState() {
        val script = HostapdConfig.killStaleScript()
        assertTrue(script.contains("hostapd"))
        assertTrue(script.contains("dnsmasq"))
        assertTrue(script.contains(HostapdConfig.LEASES_PATH))
    }

    @Test fun leasesParseAndFilterByExpiry() {
        val now = 1_700_000_000L
        val text = """
            ${now + 3600} aa:bb:cc:dd:ee:ff 192.168.42.10 iphone *
            ${now - 60} 11:22:33:44:55:66 192.168.42.11 stale *
            0 22:33:44:55:66:77 192.168.42.12 infinite *
            garbage line
        """.trimIndent()
        val parsed = HostapdConfig.parseLeases(text)
        assertEquals(3, parsed.size)
        val active = HostapdConfig.activeLeases(text, now)
        assertEquals(2, active.size)
        assertEquals(listOf("192.168.42.10", "192.168.42.12"), active.map { it.ipAddress })
    }

    @Test fun emptyLeaseFileMeansNoClients() {
        assertTrue(HostapdConfig.parseLeases("").isEmpty())
        assertFalse(HostapdConfig.activeLeases("", 1L).isNotEmpty())
    }

    @Test fun binaryProbeParsesResolvedPaths() {
        val parsed = HostapdConfig.parseBinaryProbe(
            "hostapd=/system/bin/hostapd\ndnsmasq=/system/xbin/dnsmasq\n",
        )
        assertEquals("/system/bin/hostapd", parsed["hostapd"])
        assertEquals("/system/xbin/dnsmasq", parsed["dnsmasq"])
    }

    @Test fun binaryProbeIgnoresShellNoiseAndMissingEntries() {
        // Interactive su sessions can add prompt/warning lines around the
        // probe output; only well-formed name=/abs/path lines count.
        val parsed = HostapdConfig.parseBinaryProbe(
            "WARNING: su binary insecure\nhostapd=/vendor/bin/hostapd\nrandom=line\ndnsmasq=relative\n",
        )
        assertEquals("/vendor/bin/hostapd", parsed["hostapd"])
        assertEquals(null, parsed["dnsmasq"])
        assertTrue(HostapdConfig.parseBinaryProbe("").isEmpty())
    }
}
