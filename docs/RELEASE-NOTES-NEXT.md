# DiPlay next release notes

Changes through DiPlay 0.2.14 are documented in [0.2.14 release notes](RELEASE-NOTES-0.2.14.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Unreleased

### Security and privacy

- **Embedded map access:** the exported map service now accepts `ATTACH` only from launchers (HOME-category apps), platform components, apps signed like DiPlay and, on debuggable builds, other debuggable apps. Other callers get `not_allowed`, before anything about the driver's sharing setting is revealed. See [Who may attach](LAUNCHER_INTEGRATION.md#who-may-attach).
- **Root hostapd hotspot, input:** a hotspot name or password containing a line break or any other control character is rejected, in Settings, in the runtime configuration and in `HostapdConfig`. Both values are written into a file hostapd reads as root, so a line break could add directives. The password limit is now counted in bytes, as hostapd does. New strings in all seven languages.
- **Root hostapd hotspot, leftovers:** the staged copy of the configuration is deleted as soon as it has been handed over, and the configuration, logs and lease state are removed when the hotspot stops or fails to start.
- **Root hostapd hotspot, process cleanup:** only processes whose name is exactly `hostapd` or `dnsmasq` are killed, no longer every process whose line merely contains the word.

### Reliability

- **Root hostapd hotspot:** a failed or cancelled start now stops what it started, and restores Wi-Fi if the backend had switched it on. Closing a hotspot that never started no longer kills the unit's own processes or takes `wlan0` down.
- **Bluetooth reset:** the adapter is always switched back on after the reset turned it off, including when the start is cancelled or interrupted while waiting. Before, it could stay off.

### Documentation

- [Auto-update](AUTO_UPDATE.md#testing-a-build-before-everyone-gets-it--先给测试用户的预发布): how to publish a pre-release so neither update path offers it.

### Not changed in this batch

- `UsbAutoConfirmService` still decides from the dialog text. Tying it to DiPlay's own requests would stop it working for prompts the system raises on USB attach, so that needs device testing first.
- `usesCleartextTraffic`, the update mirrors and the broad permission list are unchanged: each is relied on by a feature (LAN remote MFi over HTTP, mirrors for users without direct GitHub access, overlay/usage-access map card).
