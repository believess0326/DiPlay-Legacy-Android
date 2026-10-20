package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN")
class UsbBtPairingDialogTest {
    @Test fun chinesePairingDialogConfirmsExactlyOnceEvenAfterCountdown() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val decisions = mutableListOf<Boolean>()
        UsbBtPairingDialog.show(activity, "11:22:33:44:55:66", 1234, decisions::add)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("确认蓝牙配对", shadowOf(dialog).title.toString())
        assertEquals("将在 12 秒后自动确认", activity.getString(R.string.settings_usb_pairing_countdown, 12))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertEquals(listOf(true), decisions)
        activity.finish()
    }
}
