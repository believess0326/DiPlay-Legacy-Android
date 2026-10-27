package com.shilapi.xcertplay

/**
 * Which apps may attach to [MapEmbedService].
 *
 * The service is exported, and what it hands out is the live CarPlay map: the
 * route and the position of the car. The driver's "Share the live map with
 * other launchers" switch is the consent, but it says nothing about *who* may
 * ask, so without this check any installed app could bind to the service while
 * sharing is on and receive the map surface. Only these callers are accepted:
 *
 *  - DiPlay itself;
 *  - platform components (uids outside the app range, such as the system uid);
 *  - apps signed with the same certificate as DiPlay;
 *  - launchers: apps with an activity in the HOME category, which is what the
 *    embedding protocol is for (docs/LAUNCHER_INTEGRATION.md);
 *  - other debuggable apps, but only when this DiPlay build is itself
 *    debuggable, so that sample hosts can be tried on a test build.
 *
 * Declaring a HOME activity is visible to the user (the app then shows up as a
 * launcher choice), but it is not a signature check; the driver's switch stays
 * the consent for sharing. This class holds only the decision, so it needs no
 * Android runtime; [MapEmbedService] gathers the facts.
 */
internal object MapEmbedCallerPolicy {
    /** Facts about the calling uid, as gathered by [MapEmbedService]. */
    data class Caller(
        val isThisApp: Boolean,
        val isSystemUid: Boolean,
        val sharesOurSignature: Boolean,
        val isLauncher: Boolean,
        val isDebuggablePeer: Boolean,
    )

    /** A caller that qualifies for nothing; the starting point for building real ones. */
    val UNKNOWN = Caller(
        isThisApp = false,
        isSystemUid = false,
        sharesOurSignature = false,
        isLauncher = false,
        isDebuggablePeer = false,
    )

    /**
     * True for uids below the application range (root, system, shell, ...) on
     * any Android user. A negative uid means the message did not arrive through
     * binder, so there is no caller to vouch for and it is not a system uid.
     */
    fun isSystemUid(uid: Int): Boolean = uid >= 0 && uid % PER_USER_RANGE < FIRST_APPLICATION_UID

    fun isAllowed(caller: Caller, thisAppIsDebuggable: Boolean): Boolean =
        caller.isThisApp ||
            caller.isSystemUid ||
            caller.sharesOurSignature ||
            caller.isLauncher ||
            (thisAppIsDebuggable && caller.isDebuggablePeer)

    // android.os.UserHandle.PER_USER_RANGE and android.os.Process.FIRST_APPLICATION_UID.
    private const val PER_USER_RANGE = 100_000
    private const val FIRST_APPLICATION_UID = 10_000
}
