package com.shilapi.xcertplay

import android.graphics.Color

/**
 * The screen before CarPlay's video is DiPlay's own chrome, not CarPlay content:
 * the rest of the app is dark in both day and night mode, and a near-white
 * connecting screen glares against it (issue #297). The waiting screen is
 * therefore always dark; CarPlay's own day/night mode still applies to the
 * video once the session starts.
 */
internal data class WaitingScreenColors(val background: Int, val text: Int, val secondary: Int) {
    companion object {
        fun of(): WaitingScreenColors =
            WaitingScreenColors(Color.rgb(12, 17, 27), Color.rgb(241, 245, 252), Color.rgb(168, 182, 202))
    }
}
