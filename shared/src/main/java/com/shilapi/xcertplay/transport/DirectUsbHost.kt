package com.shilapi.xcertplay.transport

import android.os.SystemClock

/**
 * Direct-mode wired bring-up (issue #266 phase 2): the same sequence the framework
 * path runs — open, inspect descriptors, vendor re-enumeration when the CarPlay
 * configuration is absent, select configuration, claim USBMUX — but over a raw
 * usbfs fd from sysfs discovery instead of UsbManager.
 *
 * Every step logs a numbered `direct step N` line through [onDiagnostic]. On a unit
 * whose UsbManager is broken these lines are the only pinpointing evidence: the last
 * step printed before a failure is where bring-up stopped.
 */
internal class DirectUsbHost(
    private val enumerator: SysfsUsbEnumerator = SysfsUsbEnumerator(),
    private val onDiagnostic: (String) -> Unit,
) {
    sealed class Result {
        data class Connected(val session: Iap2UsbSession, val device: SysfsUsbDevice) : Result()
        data class Failed(val error: IphoneUsbException) : Result()
    }

    private fun step(message: String) {
        onDiagnostic("direct step: $message")
    }

    fun openSession(initial: SysfsUsbDevice): Result {
        step(
            "1/$STEP_TOTAL sysfs candidate vid=0x${initial.vendorId.toString(16)} " +
                "pid=0x${initial.productId.toString(16)} node=${initial.usbfsPath} sysfs=${initial.sysfsName}",
        )
        var device = initial
        val first = openConnection(device) ?: return Result.Failed(
            IphoneUsbException.DeviceUnavailable("direct usbfs node ${device.usbfsPath} could not be opened"),
        )
        val firstConfiguration = try {
            inspect(first)
        } catch (error: IphoneUsbException) {
            first.release()
            return Result.Failed(error)
        }
        if (firstConfiguration != null) {
            return finish(first, device, firstConfiguration)
        }
        step("4/$STEP_TOTAL no CarPlay configuration yet; requesting vendor re-enumeration")
        val requested = requestVendorReenumeration(first)
        first.release()
        if (!requested) {
            return Result.Failed(
                IphoneUsbException.DeviceUnavailable(
                    "iPhone did not answer the CarPlay vendor re-enumeration request",
                ),
            )
        }
        val reappeared = pollForReappearance(device)
            ?: return Result.Failed(
                IphoneUsbException.DeviceUnavailable(
                    "iPhone did not reappear in sysfs after vendor re-enumeration (30s)",
                ),
            )
        device = reappeared
        step("5/$STEP_TOTAL iPhone reappeared at ${device.usbfsPath} sysfs=${device.sysfsName}; reopening")
        val second = openConnection(device) ?: return Result.Failed(
            IphoneUsbException.DeviceUnavailable(
                "direct usbfs node ${device.usbfsPath} could not be reopened after re-enumeration",
            ),
        )
        val secondConfiguration = try {
            inspect(second)
        } catch (error: IphoneUsbException) {
            second.release()
            return Result.Failed(error)
        } ?: run {
            second.release()
            return Result.Failed(
                IphoneUsbException.Protocol(
                    "re-enumerated iPhone exposes no USBMUX CarPlay configuration (direct mode)",
                ),
            )
        }
        return finish(second, device, secondConfiguration)
    }

    /** Builds the session over an open connection that already shows a CarPlay configuration. */
    private fun finish(
        connection: RawSharedUsbConnection, device: SysfsUsbDevice, configuration: CarPlayUsbConfiguration,
    ): Result = try {
        val session = buildSession(connection, device, configuration)
        step("9/$STEP_TOTAL iAP2 USB session ready over direct fd=${connection.fileDescriptor}")
        Result.Connected(session, device)
    } catch (error: IphoneUsbException) {
        connection.release()
        Result.Failed(error)
    } catch (error: RuntimeException) {
        connection.release()
        Result.Failed(IphoneUsbException.DeviceUnavailable("direct USB bring-up failed", error))
    }

    private fun openConnection(device: SysfsUsbDevice): RawSharedUsbConnection? {
        step("2/$STEP_TOTAL opening node ${device.usbfsPath} (root grant allowed)")
        return RawSharedUsbConnection.open(device, onDiagnostic)
    }

    /** Reads descriptors and picks the CarPlay configuration; null when absent. */
    private fun inspect(connection: SharedUsbConnection): CarPlayUsbConfiguration? {
        try {
            val info = RawUsbDescriptors.readDeviceInfo(connection, onDiagnostic)
                ?: throw IphoneUsbException.DeviceUnavailable("iPhone did not answer descriptor reads (direct mode)")
            val configurations = RawUsbDescriptors.readConfigurations(connection, info.configurationCount, onDiagnostic)
            step("3/$STEP_TOTAL parsed ${configurations.size} configuration(s): ids=${configurations.map { it.id }}")
            val chosen = IphoneCarPlayConfiguration.choose(configurations)
            if (chosen != null) {
                step("3/$STEP_TOTAL CarPlay configuration id=${chosen.id} interfaces=${IphoneCarPlayConfiguration.describe(chosen)}")
            }
            return chosen
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            // Descriptor parsing builds framework model objects reflectively; a ROM whose
            // hidden constructors differ must surface as a clean failure, not a leaked fd.
            throw IphoneUsbException.DeviceUnavailable("direct descriptor inspection failed", error)
        }
    }

    /** The 0x52 vendor request the framework transition sends; true when the iPhone answered. */
    private fun requestVendorReenumeration(connection: SharedUsbConnection): Boolean {
        val response = ByteArray(VENDOR_RESPONSE_LENGTH)
        var stalled = 0
        for (attempt in 1..VENDOR_MAX_ATTEMPTS) {
            val transferred = connection.controlTransfer(
                USB_VENDOR_DEVICE_IN, CARPLAY_CONFIGURATION_REQUEST, 0,
                CARPLAY_CONFIGURATION_INDEX, response, response.size, CONTROL_TIMEOUT_MILLIS,
            )
            step("4/$STEP_TOTAL vendor re-enumeration attempt=$attempt transferred=$transferred")
            if (transferred == response.size) return true
            if (transferred < 0) stalled++
            if (attempt < VENDOR_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(200L * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        step("4/$STEP_TOTAL vendor request unanswered (stalls=$stalled); the iPhone may have refused (cable/MFi)")
        return false
    }

    /** Waits for the phone to vanish and reappear in sysfs with a fresh enumeration. */
    private fun pollForReappearance(previous: SysfsUsbDevice): SysfsUsbDevice? {
        val deadline = SystemClock.elapsedRealtime() + REAPPEAR_TIMEOUT_MILLIS
        var seenGone = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = enumerator.findMatching(IphoneUsbMatcher.appleVendor())
            val present = current.firstOrNull { it.busNumber == previous.busNumber }
            if (present == null) {
                if (!seenGone) {
                    seenGone = true
                    step("5/$STEP_TOTAL iPhone left the bus; waiting for re-enumeration")
                }
            } else if (seenGone || present.deviceNumber != previous.deviceNumber) {
                return present
            }
            try {
                Thread.sleep(REAPPEAR_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    /** The kernel's own view of the active configuration, from sysfs; null when unreadable. */
    private fun readKernelConfiguration(device: SysfsUsbDevice): Int? = runCatching {
        java.io.File("/sys/bus/usb/devices/${device.sysfsName}/bConfigurationValue")
            .readText().trim().toIntOrNull()
    }.getOrNull()

    /** Polls sysfs until the kernel reports [target] as the active configuration. */
    private fun awaitKernelConfiguration(device: SysfsUsbDevice, target: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + KERNEL_CONFIG_VERIFY_MILLIS
        while (true) {
            if (readKernelConfiguration(device) == target) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try {
                Thread.sleep(KERNEL_CONFIG_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun buildSession(
        connection: RawSharedUsbConnection, device: SysfsUsbDevice,
        configuration: CarPlayUsbConfiguration,
    ): Iap2UsbSession {
        // Issue #266 field report (SoFIA 3GR): when the kernel already holds the target
        // configuration, a redundant usbfs SET_CONFIGURATION only pokes the state
        // machine — skip straight to the claim. And the usbfs call can report success
        // while the kernel keeps the old configuration (its interfaces never appear,
        // so the claim below fails ENOENT): verify against sysfs, and on a mismatch
        // fall back to the root sysfs write of the same value before giving up.
        if (readKernelConfiguration(device) == configuration.id) {
            step(
                "6/$STEP_TOTAL kernel already at configuration ${configuration.id} (sysfs); " +
                    "skipping SET_CONFIGURATION",
            )
        } else {
            val selected = LegacyUsbHostCompat.selectConfiguration(
                connection, configuration, onDiagnostic = onDiagnostic,
            )
            step(
                "6/$STEP_TOTAL setConfiguration target=${configuration.id} ok=${selected.selected} " +
                    "active=${selected.activeConfiguration ?: "unknown"} errno=${selected.errno ?: "none"}",
            )
            if (!selected.selected) {
                throw IphoneUsbException.DeviceUnavailable(
                    "USB CarPlay configuration ${configuration.id} could not be selected (direct mode); " +
                        "activeConfig=${selected.activeConfiguration ?: "unknown"}" +
                        (selected.errno?.let { " (usbfs errno $it)" } ?: ""),
                )
            }
            if (!awaitKernelConfiguration(device, configuration.id)) {
                step(
                    "6/$STEP_TOTAL kernel still at configuration " +
                        "${readKernelConfiguration(device) ?: "unknown"} after SET_CONFIGURATION; " +
                        "trying root sysfs write",
                )
                val wrote = DirectUsbAccess.writeConfigurationViaRoot(device.sysfsName, configuration.id)
                step(
                    "6/$STEP_TOTAL root sysfs write ok=$wrote; kernel now at " +
                        "configuration ${readKernelConfiguration(device) ?: "unknown"}",
                )
                if (!awaitKernelConfiguration(device, configuration.id)) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "kernel did not adopt USB configuration ${configuration.id} (direct mode); " +
                            "sysfs still shows ${readKernelConfiguration(device) ?: "unknown"}",
                    )
                }
            }
        }
        val usbMux = IphoneCarPlayConfiguration.usbMuxInterface(configuration)
            ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
        val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
            ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
        step(
            "7/$STEP_TOTAL USBMUX iface=${usbMux.id} alt=${IphoneCarPlayConfiguration.alternateSetting(usbMux)} " +
                "out=0x${endpoints.first.address.toString(16)} in=0x${endpoints.second.address.toString(16)}",
        )
        var claim = LegacyUsbHostCompat.claim(connection, usbMux)
        var attempt = 1
        while (!claim.claimed && attempt < USB_CLAIM_MAX_ATTEMPTS) {
            attempt++
            step("8/$STEP_TOTAL USBMUX claim retry attempt=$attempt errno=${claim.errno ?: "none"}")
            try {
                Thread.sleep(USB_CLAIM_RETRY_DELAY_MILLIS)
            } catch (_: InterruptedException) {
                break
            }
            claim = LegacyUsbHostCompat.claim(connection, usbMux)
        }
        val activeAfterClaim = LegacyUsbHostCompat.activeConfiguration(connection)
        step(
            "8/$STEP_TOTAL USBMUX claim ok=${claim.claimed} errno=${claim.errno ?: "none"} " +
                "activeConfig=${activeAfterClaim ?: "unknown"}",
        )
        if (!claim.claimed) {
            throw IphoneUsbException.DeviceUnavailable(
                "could not claim USBMUX interface ${usbMux.id} (direct mode)" +
                    (claim.errno?.let { " (usbfs errno $it)" } ?: ""),
            )
        }
        return Iap2UsbSession(
            sharedConnection = connection,
            claimedInterface = usbMux,
            outEndpoint = endpoints.first,
            inEndpoint = endpoints.second,
            onDiagnostic = onDiagnostic,
            carPlayConfiguration = configuration,
        )
    }

    private companion object {
        const val STEP_TOTAL = 9
        const val USB_VENDOR_DEVICE_IN = 0xc0
        const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        const val CARPLAY_CONFIGURATION_INDEX = 0x0004
        const val VENDOR_RESPONSE_LENGTH = 1
        const val VENDOR_MAX_ATTEMPTS = 3
        const val CONTROL_TIMEOUT_MILLIS = 1_000
        const val USB_CLAIM_MAX_ATTEMPTS = 4
        const val USB_CLAIM_RETRY_DELAY_MILLIS = 400L
        const val REAPPEAR_TIMEOUT_MILLIS = 30_000L
        /** Issue #266: how long the kernel gets to adopt a configuration in sysfs. */
        const val KERNEL_CONFIG_VERIFY_MILLIS = 3_000L
        const val KERNEL_CONFIG_POLL_MILLIS = 250L
        const val REAPPEAR_POLL_MILLIS = 250L
    }
}
