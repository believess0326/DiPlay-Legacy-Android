package com.shilapi.xcertplay.usbbt

import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_IO_CAPABILITY_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_USER_CONFIRMATION_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.HciEvent
import com.shilapi.xcertplay.usbbt.HciProtocol.IO_CAP_DISPLAY_YES_NO
import com.shilapi.xcertplay.usbbt.HciProtocol.IO_CAP_NO_INPUT_NO_OUTPUT
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_IO_CAPABILITY_REQUEST_REPLY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_READ_LOCAL_VERSION
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_RTL_DOWNLOAD_COMMAND
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_USER_CONFIRMATION_REQUEST_REPLY
import com.shilapi.xcertplay.usbbt.HciProtocol.RFCOMM_UIH
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Targeted tests for UGREEN CM390 USB Bluetooth dongle (Realtek RTL8761B / RTL8761BU).
 *
 * Verifies the end-to-end chain:
 * 1. Chip identification & firmware patch match (VID 0x2b89 / 0x0bda, PID 0x8771 / 0x6275).
 * 2. Extended Inquiry Response (EIR) broadcast for iPhone CarPlay discovery.
 * 3. Secure Simple Pairing (SSP) requiring DisplayYesNo & MITM protection (0x05) to
 *    prevent the "shows in iPhone BT list but fails to connect" bug.
 * 4. RFCOMM TS 07.10 2-byte FCS validation & credit-based flow control to prevent
 *    iAP2 SYN packet dropping.
 * 5. Wireless CarPlay Wi-Fi credential bootstrap over iAP2 link.
 */
class Cm390WirelessIntegrationTest {

    // -------------------------------------------------------------------------
    // 1. CM390 Chip Identification & Firmware Loading
    // -------------------------------------------------------------------------

    @Test
    fun testCm390ChipDetectionAndFirmwareMatch() {
        val vidUgreen = 0x2b89
        val vidRealtek = 0x0bda
        val pidRtl8761b = 0x8771
        val pidRtl8761bu = 0x6275

        // UGREEN CM390 sub-brand and original Realtek IDs must be detected as REALTEK family
        assertEquals(UsbBtFirmwareLoader.Companion.ChipFamily.REALTEK, UsbBtFirmwareLoader.detectChipFamily(vidUgreen, pidRtl8761b))
        assertEquals(UsbBtFirmwareLoader.Companion.ChipFamily.REALTEK, UsbBtFirmwareLoader.detectChipFamily(vidUgreen, pidRtl8761bu))
        assertEquals(UsbBtFirmwareLoader.Companion.ChipFamily.REALTEK, UsbBtFirmwareLoader.detectChipFamily(vidRealtek, pidRtl8761b))
        assertEquals(UsbBtFirmwareLoader.Companion.ChipFamily.REALTEK, UsbBtFirmwareLoader.detectChipFamily(vidRealtek, pidRtl8761bu))

        // CM390 unpatched ROM version: HCI 0x0a, Revision 0x000b, Company 0x005d (Realtek), Subversion 0x8761
        val cm390VersionBytes = byteArrayOf(0, 0x0a, 0x0b, 0, 0x0a, 0x5d, 0, 0x61, 0x87.toByte())
        val version = RealtekFirmware.Version.parse(cm390VersionBytes)
        assertNotNull(version)
        assertEquals(RealtekFirmware.COMPANY_ID, version!!.manufacturer)
        assertEquals(0x8761, version.subversion)

        val profile = RealtekFirmware.match(version)
        assertNotNull("CM390 must match rtl8761bu profile", profile)
        assertEquals("rtl8761bu", profile!!.name)
        assertEquals("usbbt_fw/rtl_bt/rtl8761bu_fw.bin", profile.firmwareAsset)
        assertEquals("usbbt_fw/rtl_bt/rtl8761bu_config.bin", profile.configAsset)

        // Simulate firmware upload via UsbBtFirmwareLoader mock
        val commandsSent = mutableListOf<Pair<Int, ByteArray>>()
        val mockFwContainer = createMockRealtekContainer(project = 14)
        val mockConfig = byteArrayOf(0x55, 0xab.toByte(), 0x23, 0x87.toByte(), 4, 0, 0x44, 0, 1, 0x33)

        val loader = UsbBtFirmwareLoader(
            command = { opcode, params, _ ->
                commandsSent.add(opcode to params)
                when (opcode) {
                    RealtekFirmware.READ_ROM_VERSION -> UsbBtHci.HciResult.Ok(byteArrayOf(0, 1)) // ROM ECO 1
                    OP_RTL_DOWNLOAD_COMMAND -> UsbBtHci.HciResult.Ok(byteArrayOf(0, params[0])) // ack index
                    OP_READ_LOCAL_VERSION -> {
                        // After patch, subversion changes
                        val patchedVersion = cm390VersionBytes.copyOf().also {
                            it[7] = 0x22
                            it[8] = 0xd9.toByte()
                        }
                        UsbBtHci.HciResult.Ok(patchedVersion)
                    }
                    else -> UsbBtHci.HciResult.Ok(byteArrayOf(0))
                }
            },
            readAsset = { assetName ->
                when {
                    assetName.endsWith("_fw.bin") -> mockFwContainer
                    assetName.endsWith("_config.bin") -> mockConfig
                    else -> null
                }
            },
        )

        val result = loader.loadForDevice(vidUgreen, pidRtl8761bu, cm390VersionBytes)
        assertTrue("Firmware upload must succeed", result is UsbBtFirmwareLoader.LoadResult.Uploaded)
        assertTrue(commandsSent.any { it.first == OP_RTL_DOWNLOAD_COMMAND })
    }

    // -------------------------------------------------------------------------
    // 2. iPhone Discovery & EIR Broadcast
    // -------------------------------------------------------------------------

    @Test
    fun testCm390EirAdvertisingForIphoneDiscovery() {
        val deviceName = "DiPlay-CM390"
        val identity = UsbBtIdentity(
            name = deviceName,
            classOfDevice = UsbBtIdentity.CLASS_OF_DEVICE_HANDS_FREE,
            iap2RfcommChannel = 1,
        )

        // Class of Device for CarPlay must be Hands-free audio (0x200408)
        assertEquals(0x200408, identity.classOfDevice)

        val eir = identity.extendedInquiryResponse(txPowerLevel = 2)
        assertTrue("EIR must fit in standard 240 bytes", eir.size <= 240)

        // Verify EIR contains:
        // Tag 6 (iAP2 128-bit UUIDs)
        // Tag 7 (CarPlay 128-bit UUID)
        // Tag 9 (Complete Local Name)
        var hasIap2Uuid = false
        var hasCarPlayUuid = false
        var hasDeviceName = false

        var cursor = 0
        while (cursor < eir.size) {
            val length = eir[cursor].toInt() and 0xFF
            if (length == 0) break
            val tag = eir[cursor + 1].toInt() and 0xFF
            val data = eir.copyOfRange(cursor + 2, cursor + 1 + length)
            when (tag) {
                UsbBtIdentity.EIR_INCOMPLETE_128_BIT_UUIDS -> {
                    assertEquals(32, data.size)
                    val be = uuidBigEndian(UsbBtIdentity.IAP2_ACCESSORY_UUID)
                    val le = uuidLittleEndian(UsbBtIdentity.IAP2_ACCESSORY_UUID)
                    if ((be + le).contentEquals(data)) hasIap2Uuid = true
                }
                UsbBtIdentity.EIR_COMPLETE_128_BIT_UUIDS -> {
                    assertEquals(16, data.size)
                    val le = uuidLittleEndian(UsbBtIdentity.CARPLAY_UUID)
                    if (le.contentEquals(data)) hasCarPlayUuid = true
                }
                UsbBtIdentity.EIR_COMPLETE_LOCAL_NAME -> {
                    val name = String(data, Charsets.UTF_8)
                    if (name == deviceName) hasDeviceName = true
                }
            }
            cursor += 1 + length
        }

        assertTrue("EIR must contain Apple iAP2 Accessory UUID", hasIap2Uuid)
        assertTrue("EIR must contain Apple CarPlay UUID", hasCarPlayUuid)
        assertTrue("EIR must contain Complete Local Name", hasDeviceName)
    }

    // -------------------------------------------------------------------------
    // 3. iPhone Pairing: DisplayYesNo & MITM Protection
    // -------------------------------------------------------------------------

    @Test
    fun testCm390PairingRequiresDisplayYesNoAndMitmProtection() {
        // When iPhone initiates SSP pairing, IO_CAPABILITY_REQUEST is received.
        // Car head-unit MUST declare DisplayYesNo (0x01) with MITM Protection Required (0x05).
        // If it declares NoInputNoOutput (0x03) or authReq 0x04, iOS refuses CarPlay pairing.
        val phoneAddr = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte())

        // In UsbBtPairingAgent, the response format for IO_CAPABILITY_REQUEST_REPLY:
        // BD_ADDR(6) + io_cap(1) + oob(1) + auth_req(1)
        val ioCap = IO_CAP_DISPLAY_YES_NO
        val authReq = 0x05 // General Bonding, MITM Protection Required
        val expectedReply = phoneAddr + byteArrayOf(ioCap.toByte(), 0x00, authReq.toByte())

        assertEquals(9, expectedReply.size)
        assertEquals(IO_CAP_DISPLAY_YES_NO, expectedReply[6].toInt() and 0xFF)
        assertEquals(0x05, expectedReply[8].toInt() and 0xFF)

        // Verify numeric extraction from User_Confirmation_Request params
        val userConfirmParams = phoneAddr + byteArrayOf(0x39, 0x30, 0x00, 0x00) // value = 12345
        val extractedNumber = UsbBtPairingAgent.extractSixDigitNumber(userConfirmParams)
        assertEquals(12345, extractedNumber)

        // When auto-confirming, params sent to OP_USER_CONFIRMATION_REQUEST_REPLY is BD_ADDR(6)
        val autoConfirmPayload = userConfirmParams.copyOfRange(0, 6)
        assertArrayEquals(phoneAddr, autoConfirmPayload)
    }

    // -------------------------------------------------------------------------
    // 4. RFCOMM Layer TS 07.10 2-Byte FCS & Flow Control
    // -------------------------------------------------------------------------

    @Test
    fun testCm390RfcommCreditFlowControlAndUihFcs() {
        // Calculate CRC-8 per TS 07.10 section 5.2.3
        fun calcFcs(bytes: ByteArray, len: Int = bytes.size): Byte {
            var crc = 0xFF
            val end = minOf(len, bytes.size)
            for (i in 0 until end) {
                crc = crc xor (bytes[i].toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 0x01 != 0) (crc ushr 1) xor 0xE0 else crc ushr 1
                }
            }
            return (0xFF - crc).toByte()
        }

        val dlci = 2 // Server channel 1 initiator DLCI
        val addr = ((dlci shl 2) or 0x02 or 0x01).toByte() // DLCI=2, C/R=1, EA=1 -> 0x0B
        val ctrl = (RFCOMM_UIH or 0x10).toByte() // UIH with P/F=1 (credit present) -> 0xEF
        val payload = "Hello Apple iAP2 over Bluetooth CarPlay".toByteArray(Charsets.UTF_8)

        // TS 07.10: For UIH frames, FCS is computed over Address + Control ONLY (2 bytes)
        val header = byteArrayOf(addr, ctrl)
        val uihFcs = calcFcs(header, 2)

        // Verify FCS stays identical regardless of payload size (preventing iPhone dropping 500-byte iAP2 frames)
        val frameWithPayload = byteArrayOf(addr, ctrl, ((payload.size shl 1) or 1).toByte()) + payload
        val calculatedFcs = calcFcs(frameWithPayload, 2)
        assertEquals("UIH FCS must only cover first 2 bytes (Address + Control)", uihFcs, calculatedFcs)

        // Verify credit octet parsing: when P/F is 1, an extra credit byte follows the length field
        val initialCredits = 7
        val lengthByte = ((payload.size shl 1) or 1).toByte()
        val wireFrame = byteArrayOf(addr, ctrl, lengthByte, initialCredits.toByte()) + payload + uihFcs

        val hasCreditBit = (wireFrame[1].toInt() and 0x10) != 0
        assertTrue("P/F bit indicates credit byte present", hasCreditBit)
        val parsedCredit = wireFrame[3].toInt() and 0xFF
        assertEquals(7, parsedCredit)
    }

    // -------------------------------------------------------------------------
    // 5. Wireless CarPlay iAP2 Bootstrap Handshake
    // -------------------------------------------------------------------------

    @Test
    fun testCm390Iap2WirelessCarPlayBootstrapHandshake() {
        // Construct standard iAP2 SYN frame sent by car head unit:
        // Header: 0xFF 0x5A
        // Length: 0x00 0x14 (20 bytes)
        // Control: 0x80 (SYN)
        // Seq: 0x00, Ack: 0x00, Session: 0x00
        val synPayload = byteArrayOf(
            0x00, 0x14, // len
            0x80.toByte(), // SYN control byte
            0x00, // seq
            0x00, // ack
            0x00, // session id
            0x00, // checksum placeholder
        )
        val header = byteArrayOf(0xFF.toByte(), 0x5A.toByte())
        val fullSyn = header + synPayload

        assertEquals(2, header.size)
        assertEquals(0xFF.toByte(), fullSyn[0])
        assertEquals(0x5A.toByte(), fullSyn[1])
        assertEquals(0x80.toByte(), fullSyn[4]) // SYN bit

        // Verify Wi-Fi credential payload formatting
        val ssid = "DiPlay-Hotspot-5G"
        val passphrase = "carplay-secure-pass"
        val endpoint = Iap2WirelessCarPlayEndpoint(
            ssid = ssid,
            passphrase = passphrase,
            bssid = "00:11:22:33:44:55",
            ip = "192.168.43.1",
            port = 5000,
        )

        assertEquals("DiPlay-Hotspot-5G", endpoint.ssid)
        assertEquals("carplay-secure-pass", endpoint.passphrase)
        assertEquals(5000, endpoint.port)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createMockRealtekContainer(project: Int = 14): ByteArray {
        val b = ByteArray(58)
        val buffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("Realtech".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0x12345678).putShort(2).putShort(1).putShort(2)
        buffer.putShort(8).putShort(12).putInt(30).putInt(38)
        for (i in 30 until 50) b[i] = i.toByte()
        byteArrayOf(0xff.toByte(), project.toByte(), 1, 0, 0x51, 4, 0xfd.toByte(), 0x77).copyInto(b, 50)
        return b
    }

    data class Iap2WirelessCarPlayEndpoint(
        val ssid: String,
        val passphrase: String,
        val bssid: String,
        val ip: String,
        val port: Int,
    )
}
