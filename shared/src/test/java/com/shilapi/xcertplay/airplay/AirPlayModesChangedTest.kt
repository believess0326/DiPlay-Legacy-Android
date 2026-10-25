package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayModesChangedTest {

    @Test
    fun handleModesChangedUpdatesAppStatesAndNotifiesListener() {
        var observedAppStates: List<Map<String, Any?>>? = null
        var observedResources: List<Map<String, Any?>>? = null

        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onModesChanged(
                    session: AirPlaySession,
                    appStates: List<Map<String, Any?>>,
                    resources: List<Map<String, Any?>>,
                ) {
                    observedAppStates = appStates
                    observedResources = resources
                }
            },
            media = object : AirPlayMediaHandler {},
        )

        try {
            val reqBody = BplistCodec.encode(
                mapOf(
                    "appStates" to listOf(
                        mapOf("appStateID" to 3, "state" to true),
                    ),
                ),
            )
            val request = RtspMessage.Request(
                method = "POST",
                path = "/modesChanged",
                protocol = "RTSP/1.0",
                headers = mapOf("content-length" to reqBody.size.toString()),
                body = reqBody,
            )

            val handleMethod = session.javaClass.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply {
                isAccessible = true
            }
            val response = handleMethod.invoke(session, request) as RtspMessage.Response

            assertEquals(200, response.status ?: 200)
            assertEquals("application/x-apple-binary-plist", response.headers["Content-Type"])

            // Verify listener observed the update
            assertNotNull(observedAppStates)
            assertEquals(1, observedAppStates?.size)
            assertEquals(3, (observedAppStates?.first()?.get("appStateID") as? Number)?.toInt())
            assertEquals(true, observedAppStates?.first()?.get("state"))

            // Verify session cached currentAppStates
            val current = session.currentAppStates()
            val navi = current.find { (it["appStateID"] as? Number)?.toInt() == 3 }
            assertNotNull(navi)
            assertEquals(true, navi?.get("state"))
        } finally {
            session.close()
        }
    }

    @Test
    fun handleCommandModesChangedNotifiesListenerAndUpdatesAppStates() {
        var observedAppStates: List<Map<String, Any?>>? = null
        var observedResources: List<Map<String, Any?>>? = null

        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onModesChanged(
                    session: AirPlaySession,
                    appStates: List<Map<String, Any?>>,
                    resources: List<Map<String, Any?>>,
                ) {
                    observedAppStates = appStates
                    observedResources = resources
                }
            },
            media = object : AirPlayMediaHandler {},
        )

        try {
            val commandBody = BplistCodec.encode(
                mapOf(
                    "type" to "modesChanged",
                    "params" to mapOf(
                        "appStates" to listOf(
                            mapOf("appStateID" to 2, "state" to true),
                        ),
                        "resources" to listOf(
                            mapOf("resourceID" to 2, "transferType" to 1),
                        ),
                    ),
                ),
            )
            val request = RtspMessage.Request(
                method = "POST",
                path = "/command",
                protocol = "RTSP/1.0",
                headers = mapOf("content-length" to commandBody.size.toString()),
                body = commandBody,
            )

            val handleMethod = session.javaClass.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply {
                isAccessible = true
            }
            val response = handleMethod.invoke(session, request) as RtspMessage.Response

            assertEquals(200, response.status ?: 200)

            // Verify listener observed both appStates and resources
            assertNotNull(observedAppStates)
            assertEquals(1, observedAppStates?.size)
            assertEquals(2, (observedAppStates?.first()?.get("appStateID") as? Number)?.toInt())

            assertNotNull(observedResources)
            assertEquals(1, observedResources?.size)
            assertEquals(2, (observedResources?.first()?.get("resourceID") as? Number)?.toInt())
            assertEquals(1, (observedResources?.first()?.get("transferType") as? Number)?.toInt())

            // Verify session cached currentAppStates
            val current = session.currentAppStates()
            val mediaState = current.find { (it["appStateID"] as? Number)?.toInt() == 2 }
            assertNotNull(mediaState)
            assertEquals(true, mediaState?.get("state"))
        } finally {
            session.close()
        }
    }
}
