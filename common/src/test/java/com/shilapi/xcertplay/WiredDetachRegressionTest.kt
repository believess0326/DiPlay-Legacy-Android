package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WiredDetachRegressionTest {
    @Test fun detachStopsOldResourcesWhileControlExecutorIsStillBusy() {
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {})
        val control = ReflectionHelpers.getField<ExecutorService>(controller, "executor")
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val csm = mock(Iap2Session::class.java)
        val mux = mock(Iap2UsbMuxHost::class.java)
        val ncm = mock(NcmUsbBridge::class.java)
        val service = mock(CarPlayVpnService::class.java)
        ReflectionHelpers.setField(controller, "csm", csm)
        ReflectionHelpers.setField(controller, "mux", mux)
        ReflectionHelpers.setField(controller, "wiredNcm", ncm)
        ReflectionHelpers.setField(controller, "vpnService", service)
        doAnswer { cleaned.countDown(); null }.`when`(csm).close()
        control.execute { entered.countDown(); unblock.await(5, TimeUnit.SECONDS) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            ReflectionHelpers.callInstanceMethod<Unit>(controller, "beginWiredTeardown")
            verify(mux).markAborted()
            verify(ncm).markAborted()
            verify(service).markWiredAborted()
            assertTrue("cleanup must not queue behind the live control loop", cleaned.await(2, TimeUnit.SECONDS))
            assertTrue(ReflectionHelpers.getField<AtomicBoolean>(controller, "wiredTeardownPending").get())
            unblock.countDown()
            control.submit {}.get(3, TimeUnit.SECONDS)
        } finally {
            unblock.countDown()
            controller.close()
            assertTrue(controller.awaitClosed(5_000))
        }
    }
}
