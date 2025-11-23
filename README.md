# MiRearScreenSwitcherEnglish (MRSS)

A one-tap rear-screen switcher for dual-screen Xiaomi devices such as Xiaomi 17 Pro / 17 Pro Max.

## License

- Versions **3.0.0 and later**: **GPL-3.0**
- Versions **before 3.0.0**: **MIT**

---

## Highlights (v3.1.3)

- Quick switch: control-center tile moves the current app to the rear screen
- Rear screenshot: take a rear-screen shot and save to Photos
- Rear recording: floating control to record the rear screen, saved to `Movies`
- Charging animation: 3D lightning container with liquid effect while plugged in
- Notification mirroring: push selected app notifications to the rear screen with privacy mode and DND following
- Works in background: tiles keep working even if MRSS is swiped away
- No root needed: powered by Shizuku
- Display tuning: DPI, rotation (0/90/180/270), optional rear screen always-on, proximity sensor guard
- Safety: hides MRSS from recents, prevents launcher from covering projected apps
- URI control: `mrss://` scheme for Tasker/MacroDroid, etc.

## Requirements

1. Dual-screen Xiaomi phone (e.g., Xiaomi 17 Pro / 17 Pro Max)
2. Shizuku installed and running  
   - Download: <https://github.com/RikkaApps/Shizuku/releases> (official APK)  
   - Start via USB ADB or Wireless Debugging (see below)

## Quick Setup

1. Install MRSS.
2. Install Shizuku APK from the official release and start the service:
   - Enable Developer options > Wireless debugging (pair if prompted).
   - Pair: `adb pair <ip>:<pair_port>` then enter the pairing code from the phone.
   - Connect: `adb connect <ip>:<debug_port>`
   - Start Shizuku: `adb -s <ip:port> shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`
3. Open Shizuku on the phone and ensure it shows “Running”.
4. Open MRSS and grant the Shizuku permission.
5. Wait for the status to show **All Set**.
6. Add quick tiles: pull down Control Center > Edit > add **Switch to Rear**, **Rear Screenshot**, **Rear Record**, **Return to Main**.

## Daily Use

- **Switch an app to rear**: open the app, pull tiles, tap **Switch to Rear**, then flip the phone.
- **Rear screenshot**: tap **Rear Screenshot**; it auto-collapses and saves to Photos.
- **Rear recording**: tap **Rear Record**, use the floating red circle to start/stop; video saves to `Movies/MRSS_*.mp4`.
- **Return to main screen**: tap the MRSS notification, use the **Return to Main** tile, or exit the rear-screen app; the notice disappears automatically. In-app, there’s also a button to return the rear app.
- **Charging animation and notifications**: toggle inside the app; notification push supports app selection, privacy mode, DND follow, and custom auto-destroy time.
- **Display tuning**: set rear DPI (recommended 260-350), rotation, always-on, and proximity cover detection inside the app.

Tips:
- Tiles keep working even if MRSS is backgrounded.
- MRSS is hidden from Recents to avoid accidental clears.
- URI controls like `mrss://switch?current=1` are supported.

## Developer Quick Steps

```bash
# Install dependencies
flutter pub get

# Debug build
flutter build apk --debug

# Release build (arm64, split per ABI, shrink/proguard)
flutter build apk --release --split-per-abi --target-platform android-arm64

# Install/run on a connected device
flutter run --release -d <device_id>
```

Release APK output: `app-release.apk`


Final APK commands:
- Debug (single): `flutter build apk --debug`
- Release (arm64 split): `flutter build apk --release --split-per-abi --target-platform android-arm64`
- Release (universal): `flutter build apk --release`
Outputs land in `build/app/outputs/flutter-apk/`.

## Technical Notes

- Flutter UI (Material 3, gradient theme, rounded corners)
- Shizuku-backed privileged shell commands
- Android Quick Settings tiles for switch/screenshot/record
- ActivityTaskManager for display switching; foreground service + wakelock; optional rear always-on
- NotificationListenerService to mirror system notifications with privacy and timing options
- Keycode wakeup to keep the rear display responsive
- Media scanner refresh for screenshots/recordings
- Dynamic animation reload for charging/notification animations
- Rear Animation Manager coordinates charging and notification animations
- Periodic monitor clears stale rear notifications when apps exit
- BroadcastReceiver for charging events
- Custom Canvas 3D lightning + liquid animation
- Uses `screencap` + `screenrecord`; supports `mrss://` URI commands

## Permissions

- `moe.shizuku.manager.permission.API_V23`: Shizuku privileged API
- `android.permission.WAKE_LOCK`: keep rear screen awake
- `android.permission.FOREGROUND_SERVICE`: run foreground service
- `android.permission.POST_NOTIFICATIONS`: notifications (Android 13+)
- `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`: notification listener (optional)
- `android.permission.SYSTEM_ALERT_WINDOW`: overlay for recording controls
- `android.permission.QUERY_ALL_PACKAGES`: list apps for notification mirroring
- Broadcast receivers for `ACTION_POWER_CONNECTED/DISCONNECTED`

## Changelog (high level)

- **3.1.4 (WIP)**: Added “Return to Main” QS tile and in-app button; removed tutorial/CoolApk/donation/QQ UI; forced English UI; docs updated for Shizuku wireless setup.
- **3.1.3**: Full multilingual support, localized notifications/toasts, UI fixes for English.
- **3.1.2**: App selection list pinning; optimized launcher-kill timing for animations.
- **3.1.1**: ChargingService foreground keep-alive; unified kernel service notification; notification state fixes.
- **3.1.0**: Lightning charging icon; unlimited auto-destroy time; notification persistence fixes.
- **3.0.0**: GPL-3.0; new charging animation; notification mirroring; rear recording; URI support; refreshed UI.

## Team

- Author: **AntiOblivionis** (GitHub [GoldenglowSusie](https://github.com/GoldenglowSusie/))

## Credits

- [Shizuku](https://github.com/RikkaApps/Shizuku)
- Flutter team
- Xiaomi HyperOS / Xiaomi Surge OS rear-screen capability

## Copyright and Disclaimer

- App icons use Xiaomi HyperOS assets. Trademarks belong to Xiaomi; this is a third-party tool with no affiliation. Contact for removal if needed.
- CoolApk icon belongs to Beijing KuAn Network Technology Co., Ltd.; used only as a jump marker, no official partnership.
- This is an open-source project powered by Shizuku. Use at your own risk. The authors are not liable for any losses. If there is infringement, please reach out for removal.
