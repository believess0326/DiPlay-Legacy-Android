package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SysfsUsbEnumeratorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun deviceDir(name: String, vid: String, pid: String, bus: Int, dev: Int): File {
        val dir = File(temp.root, name).apply { mkdirs() }
        File(dir, "idVendor").writeText("$vid\n")
        File(dir, "idProduct").writeText("$pid\n")
        File(dir, "busnum").writeText("$bus\n")
        File(dir, "devnum").writeText("$dev\n")
        return dir
    }

    @Test fun enumeratesDevicesAndSkipsInterfaces() {
        deviceDir("1-2", "05ac", "12a8", 1, 4)
        deviceDir("1-2:1.0", "05ac", "12a8", 1, 4) // interface entry: same attrs, must be skipped
        deviceDir("usb1", "1d6b", "0002", 1, 1)
        val devices = SysfsUsbEnumerator(temp.root).enumerate()
        assertEquals(2, devices.size)
        val iphone = devices.first { it.vendorId == 0x05ac }
        assertEquals(0x12a8, iphone.productId)
        assertEquals("/dev/bus/usb/001/004", iphone.usbfsPath)
    }

    @Test fun findMatchingReturnsOnlyMatcherHits() {
        deviceDir("1-2", "05ac", "12a8", 1, 4)
        deviceDir("2-1", "0bda", "8153", 2, 3)
        val hits = SysfsUsbEnumerator(temp.root).findMatching(IphoneUsbMatcher.appleVendor())
        assertEquals(1, hits.size)
        assertEquals(0x05ac, hits[0].vendorId)
    }

    @Test fun missingAttributesExcludeTheEntry() {
        val dir = File(temp.root, "1-9").apply { mkdirs() }
        File(dir, "idVendor").writeText("05ac\n") // no idProduct/busnum/devnum
        assertTrue(SysfsUsbEnumerator(temp.root).enumerate().isEmpty())
    }

    @Test fun missingRootYieldsEmpty() {
        assertTrue(SysfsUsbEnumerator(File(temp.root, "nope")).enumerate().isEmpty())
    }
}
