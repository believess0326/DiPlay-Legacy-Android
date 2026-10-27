package com.shilapi.xcertplay

/**
 * Issue #290: counts consecutive wireless link-establishment failures (the
 * iPhone never opens AirPlay TCP) and decides when to show the visible
 * "forget the device on the iPhone and re-pair" prompt. Pure state machine:
 * the activity owns persistence (AirPlayPersistence) and restores the count
 * on start, so the streak survives process restarts — unlike the in-memory
 * startup retry budget, which resets.
 *
 * Semantics: the prompt shows exactly once per streak, when the count
 * reaches [THRESHOLD]. Further failures in the same streak do not re-show
 * it. Only a session that shows video resets the streak; a manual retry
 * does not (a retry is another attempt, not a success). NOTE: THRESHOLD is
 * stated in the user-facing prompt text; keep them in sync if it ever
 * changes.
 */
internal class WirelessLinkFailurePrompt(
    var consecutiveFailures: Int = 0,
    var promptShown: Boolean = false,
) {
    companion object {
        const val THRESHOLD = 4
    }

    /** Records one failure. Returns true exactly when the prompt should be shown now. */
    fun onFailure(): Boolean {
        consecutiveFailures += 1
        if (consecutiveFailures >= THRESHOLD && !promptShown) {
            promptShown = true
            return true
        }
        return false
    }

    /** Records a session showing video: the streak is over. */
    fun onSessionActivated() {
        consecutiveFailures = 0
        promptShown = false
    }
}
