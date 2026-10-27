package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class AudioFocusSettingsTest {
    @Test fun dependentOptionsHideWhenFocusIsOffAndRetainPreferences() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveAudioFocusEnabled(activity, false)
        AirPlayPersistence.saveAudioFocusAutoYield(activity, false)
        AirPlayPersistence.saveMediaFocusExclusive(activity, false)
        val page = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("audioFocusControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, page)
        activity.setContentView(page)
        val switches = views(page).filterIsInstance<Switch>().toList()
        assertEquals(3, switches.size)
        val focus = switches.single { it.contentDescription == activity.getString(R.string.contrib_audio_home_toggle_audio_focus) }
        val mute = switches.single { it.contentDescription == activity.getString(R.string.audio_focus_auto_yield) }
        val exclusive = switches.single { it.contentDescription == activity.getString(R.string.settings_media_focus_exclusive) }
        val dependent = mute.parent.parent as View
        assertEquals(View.GONE, dependent.visibility)
        focus.isChecked = true
        assertEquals(View.VISIBLE, dependent.visibility)
        assertFalse(mute.isChecked)
        assertFalse(exclusive.isChecked)
        mute.isChecked = true
        exclusive.isChecked = true
        focus.isChecked = false
        assertEquals(View.GONE, dependent.visibility)
        assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(activity))
        assertTrue(AirPlayPersistence.loadMediaFocusExclusive(activity))
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(activity))
        focus.isChecked = true
        assertTrue(mute.isChecked)
        assertTrue(exclusive.isChecked)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
