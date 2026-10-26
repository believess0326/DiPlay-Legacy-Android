# ECARX Android 5.1 stock hotspot

Issue #322 describes an ECARX FE-6 (`xea406m`, firmware 4.4.9(CE), API 22) whose stock tethering service checks `CHANGE_NETWORK_STATE` when `setWifiApEnabled` is called. The common manifest now requests that permission.

On API 22 devices reporting `xea406m` with the system package `ecarx.settings`, **Settings → Connection** exposes an opt-in stock-hotspot switch in MANUAL / car-hotspot mode. This path does not need local ADB authorization: its hotspot and boot-launch permissions are install-time permissions on this firmware. BYD controls retain their existing authorization path; other devices are not newly opted in.

## Setup

1. Configure the stock hotspot name and password in the car settings and select the car-hotspot mode in DiPlay.
2. Enable **Automatically turn on the car hotspot** in Connection.
3. Enable **Open after the car starts** for full boot startup. Enable **Connect when DiPlay opens** and select wireless as the default connection if automatic CarPlay connection is wanted.
4. Disable the separate hotspot helper before testing this integrated path, so that only DiPlay controls startup.

The existing startup/controller code turns off station Wi-Fi, requests the saved system hotspot, and waits for hotspot readiness. It does not replace the saved configuration or turn the hotspot off on disconnect. Changing the switch marks the next connection for applying the setting without interrupting an active session.

## Verification boundary

The owner verified stock hotspot startup and DiPlay launch after reboot using a separate helper with these permissions and the stock Wi-Fi API. That validates the device capability, not this APK. The integrated build still needs a cold-boot test on the car with the helper disabled. No USB Bluetooth behavior is changed by this patch.

For that test, retain the original APK and settings, use an appropriately signed build with the required runtime assets, and confirm the boot diagnostic, AP interface readiness, and application launch. A source-only debug APK has a separate application ID and no runtime authentication assets; its presence alone does not validate a CarPlay session. Also test the switch off and a non-MANUAL mode to verify no unsolicited hotspot startup occurs. Sleep/wake behavior is not covered by BOOT_COMPLETED.
