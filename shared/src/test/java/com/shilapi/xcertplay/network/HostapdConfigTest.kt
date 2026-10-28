package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

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

    @Test fun hostAddressProbeAcceptsBothIfconfigDialects() {
        val busybox = "wlan0     Link encap:Ethernet  HWaddr 00:11:22:33:44:55\n" +
            "          inet addr:192.168.42.1  Bcast:192.168.42.255  Mask:255.255.255.0"
        val toybox = "wlan0: flags=4163<UP,BROADCAST,RUNNING,MULTICAST>  mtu 1500\n" +
            "        inet 192.168.42.1  netmask 255.255.255.0  broadcast 192.168.42.255"
        val ipAddr = "3: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500\n" +
            "    inet 192.168.42.1/24 brd 192.168.42.255 scope global wlan0"
        assertTrue(HostapdConfig.outputShowsHostAddress(busybox))
        assertTrue(HostapdConfig.outputShowsHostAddress(toybox))
        assertTrue(HostapdConfig.outputShowsHostAddress(ipAddr))
    }

    @Test fun hostAddressProbeRejectsOtherAddressesAndEmptyOutput() {
        val other = "        inet 192.168.43.1  netmask 255.255.255.0"
        val unaddressed = "wlan0: flags=4099<UP,BROADCAST,MULTICAST>  mtu 1500"
        assertFalse(HostapdConfig.outputShowsHostAddress(other))
        assertFalse(HostapdConfig.outputShowsHostAddress(unaddressed))
        assertFalse(HostapdConfig.outputShowsHostAddress(""))
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

    @Test fun controlCharactersAreRejectedBeforeTheyReachTheRootConfig() {
        // The values are written verbatim into a file hostapd reads as root,
        // one directive per line: a line break would add directives.
        assertNotNull(HostapdConfig.error("Car\nwpa=0", "12345678", 1))
        assertNotNull(HostapdConfig.error("Car\rX", "12345678", 1))
        assertNotNull(HostapdConfig.error("DiPlay", "12345678\nwpa=0", 1))
        assertNotNull(HostapdConfig.error("DiPlay", "1234\t5678", 1))
        assertNotNull(HostapdConfig.error("DiPlay", "12345678\u007f", 1))
        assertTrue(HostapdConfig.hasControlCharacter("a\nb"))
        assertFalse(HostapdConfig.hasControlCharacter("Mon voiture 车 ü"))
        // Ordinary non-ASCII text stays usable.
        assertNull(HostapdConfig.error("My Car ü 车", "pass word ü 1", 1))
    }

    @Test fun everyRenderedLineIsExactlyOneKnownDirective() {
        val conf = HostapdConfig.renderConf("My Car", "secret-pass", 6)
        val directive = Regex(
            "^(interface|driver|ssid|hw_mode|channel|ieee80211n|auth_algs|wpa|wpa_passphrase|" +
                "wpa_key_mgmt|rsn_pairwise|max_num_sta)=[^\\r\\n]*$",
        )
        val lines = conf.lines().filter { it.isNotEmpty() }
        assertEquals(12, lines.size)
        assertTrue(lines.all { directive.matches(it) })
    }

    @Test fun passphraseLengthIsCountedInBytes() {
        // hostapd limits the passphrase to 63 bytes, not 63 characters.
        assertNotNull(HostapdConfig.error("DiPlay", "é".repeat(32), 1)) // 32 chars, 64 bytes
        assertNull(HostapdConfig.error("DiPlay", "é".repeat(31) + "x", 1)) // 63 bytes
    }

    @Test fun cleanupRemovesTheFilesThatHoldThePassphrase() {
        val script = HostapdConfig.removeGeneratedFilesScript()
        assertTrue(script.contains(HostapdConfig.CONF_PATH))
        assertTrue(script.contains(HostapdConfig.TMP_CONF_PATH))
        assertTrue(script.contains(HostapdConfig.HOSTAPD_LOG_PATH))
        assertTrue(script.contains(HostapdConfig.LEASES_PATH))
        assertEquals("rm -f ${HostapdConfig.TMP_CONF_PATH}", HostapdConfig.removeStagedConfScript())
    }

    @Test fun staleKillTargetsOnlyProcessesNamedExactlyHostapdOrDnsmasq() {
        // Runs the generated script under a POSIX shell with a fake `ps`; skipped
        // where no /bin/sh exists (for example a Windows development machine).
        assumeTrue("needs /bin/sh", File("/bin/sh").canExecute())
        val fakes = listOf(
            "ps() { printf '%s\\n'" +
                " 'USER PID PPID VSIZE RSS WCHAN PC NAME'" +
                " 'root 101 1 1 1 f 0 S /system/bin/hostapd'" +
                " 'root 102 1 1 1 f 0 S dnsmasq'" +
                " 'u0_a55 103 1 1 1 f 0 S com.example.hostapd'" +
                " 'root 104 1 1 1 f 0 S /data/misc/wifi/hostapd.conf'" +
                " 'root 105 1 1 1 f 0 S su'" +
                " 'shell 106 1 1 1 f 0 S hostapd_helper'" +
                " 'root 107 2 0 0 f 0 S [kworker/u:1]'" +
                " 'root 108 1 1 1 f 0 S hostapd -B /data/misc/wifi/hostapd.conf'" +
                " 'root x9 1 1 1 f 0 S hostapd'; }",
            "kill() { echo \"kill \$*\"; }",
            "sleep() { :; }",
            "rm() { echo \"rm \$*\"; }",
        ).joinToString("\n")
        val process = ProcessBuilder("/bin/sh", "-c", fakes + "\n" + HostapdConfig.killStaleScript())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val kills = output.lines().filter { it.startsWith("kill ") }.sorted()
        // 101: full path, 102: bare name, 108: name as the first field of a command line.
        // Not 103/104/106 (names that merely contain the word), 105/107 (other
        // processes) or "x9" (not a PID).
        assertEquals(listOf("kill -9 101", "kill -9 102", "kill -9 108"), kills)
        assertTrue(output.contains("rm -f ${HostapdConfig.LEASES_PATH} ${HostapdConfig.DNSMASQ_PID_PATH}"))
    }

    @Test fun countHostapdProcessesCountsOnlyExactProcessesAndOmitsGrep() {
        val ps = """
            USER     PID   PPID  VSIZE  RSS     WCHAN    PC         NAME
            root     101   1     1234   567     c0123456 b6f12345   /system/bin/hostapd
            wifi     102   1     1234   567     c0123456 b6f12345   hostapd
            wifi     103   1     1234   567     c0123456 b6f12345   /vendor/bin/hostapd -B /data/misc/wifi/hostapd.conf
            u0_a55   104   1     1234   567     c0123456 b6f12345   com.example.hostapd
            shell    105   1     1234   567     c0123456 b6f12345   hostapd_helper
            root     106   1     1234   567     c0123456 b6f12345   /data/misc/wifi/hostapd.conf
            root     107   1     1234   567     c0123456 b6f12345   grep hostapd
        """.trimIndent()
        assertEquals(3, HostapdConfig.countHostapdProcesses(ps))
        assertEquals(0, HostapdConfig.countHostapdProcesses(""))
    }

    @Test fun parseLaunchedPidTakesFirstDigitsOnlyLine() {
        assertEquals(1234, HostapdConfig.parseLaunchedPid("1234\n"))
        assertEquals(1234, HostapdConfig.parseLaunchedPid("sh: hostapd: not found\n1234\n"))
        assertEquals(1234, HostapdConfig.parseLaunchedPid("\n  1234  \n"))
        assertNull(HostapdConfig.parseLaunchedPid(""))
        assertNull(HostapdConfig.parseLaunchedPid("sh: hostapd: not found\n"))
        assertNull(HostapdConfig.parseLaunchedPid("pid=1234\n"))
    }

    @Test fun psLineForPidAcceptsBothPidColumnsAndRejectsSubstrings() {
        val toybox = "USER     PID   PPID  NAME\nroot     101   1     /system/bin/hostapd\n"
        val busybox = "PID   USER  VSZ   STAT  COMMAND\n101   root  1234  S     /system/bin/hostapd\n"
        assertEquals("root     101   1     /system/bin/hostapd", HostapdConfig.psLineForPid(toybox, 101))
        assertEquals("101   root  1234  S     /system/bin/hostapd", HostapdConfig.psLineForPid(busybox, 101))
        assertNull(HostapdConfig.psLineForPid(toybox, 10))
        assertNull(HostapdConfig.psLineForPid(toybox, 1011))
        assertNull(HostapdConfig.psLineForPid("", 101))
    }
}
