package com.shilapi.xcertplay.media

/**
 * Issue #346: counts consecutive video-decoder output stalls.
 *
 * The first stalls only re-ask for a keyframe (existing behavior). Once the
 * count reaches [REBUILD_AFTER_STALLS], the next recovery forces a decoder
 * rebuild even though a keyframe wait is already in progress: on the
 * reporter's T3 unit the requested keyframes never unstick the wedged
 * decoder, so the wait never clears and the plain recovery path never
 * rebuilds.
 *
 * Any rendered frame resets the count: a stall sequence broken by output is
 * not consecutive anymore. Reaching the threshold also resets the count, so
 * a persistently wedged decoder is rebuilt once per threshold stalls, not on
 * every stall event.
 */
class DecoderStallRebuildTracker {
    private var consecutiveStalls = 0

    /** Records one output stall; returns true when this stall forces a rebuild. */
    fun onStall(): Boolean {
        consecutiveStalls++
        if (consecutiveStalls >= REBUILD_AFTER_STALLS) {
            consecutiveStalls = 0
            return true
        }
        return false
    }

    /** A rendered frame breaks the stall sequence. */
    fun onOutput() {
        consecutiveStalls = 0
    }

    /** A fresh pipeline (e.g. surface change) restarts the count. */
    fun reset() {
        consecutiveStalls = 0
    }

    companion object {
        const val REBUILD_AFTER_STALLS = 3
    }
}
