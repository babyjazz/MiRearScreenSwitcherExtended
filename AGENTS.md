# AGENTS.md

Guidance for Codex (and any other coding agent) when working in this repository.

## What this is

MRSS (Mi Rear Screen Switcher) — a Flutter+Android app for Xiaomi dual-screen phones (e.g. Xiaomi 17 Pro). It projects a single custom rear pager that shows media playback, notifications, and charging animation onto the rear display. It uses Shizuku for the privileged shell access root would normally require. The Flutter layer is a thin settings/status UI; almost all real behavior lives in native Android Java services.

## Build & run

```bash
flutter pub get                 # install Dart deps

# Debug build (either works; gradle direct is faster when iterating on native code only)
flutter build apk --debug
cd android && ./gradlew assembleDebug -q

# Release
flutter build apk --release --split-per-abi --target-platform android-arm64

# Install on the connected device, preserving existing app data
adb install -r build/app/outputs/apk/debug/app-debug.apk
```

`flutter build apk --debug` writes `build/app/outputs/flutter-apk/app-debug.apk`; `./gradlew assembleDebug` writes `build/app/outputs/apk/debug/app-debug.apk`. Both are valid debug builds.

`flutter analyze` runs the Dart linter (stock `flutter_lints`; `android/` and `build/` are excluded). There is no test suite in this repo (`test/` absent) — verification is manual, on a real dual-screen device, via `adb`. The only unit test target is `RearPagerStateTest`:

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.tgwgroup.MiRearScreenSwitcher.RearPagerStateTest"
```

After any install/reinstall or `am force-stop`, the notification listener binding is dropped and must be re-granted, or `NotificationService` silently stops receiving anything:

```bash
adb shell cmd notification allow_listener com.tgwgroup.MiRearScreenSwitcher/com.tgwgroup.MiRearScreenSwitcher.NotificationService
```

## Rear pager architecture (current)

### One Activity owns the rear display
`RearHostActivity` is the only rear-screen Activity. It hosts `RearPagerView`, an AndroidX `ViewPager` with **three fixed pages**:

| Page | View | Payload |
|---|---|---|
| 0 Music | `MediaView` | packageName, title, artist, albumArtPath, isPlaying |
| 1 Notification | `NotificationView` | packageName, title, text, darkMode |
| 2 Charging | `ChargingView` | batteryLevel |

Each page embeds the exact current full-screen MRSS view plus an empty-state overlay when that feature is inactive.

### RearStack is the source of truth
`RearStack` holds one payload per feature type. Priority is ascending: `MEDIA`, `CHARGING`, `NOTIFICATION`. `RearHost.show(ctx, type, payload)` and `RearHost.hide(ctx, type)` are the **only** APIs services use. Do not launch per-feature Activities or send interrupt broadcasts.

- If the pager is already visible → `show()` updates pages and auto-switches to the highest-priority active feature.
- If the pager is alive but backgrounded behind stock rear home → `show()` brings it front with a bare `am start -n ...RearHostActivity` (no `--display 1`; the task already exists on display 1).
- If the pager is not alive → cold launch (`--display 1` when unlocked, else main-display placeholder + `am display move-stack`).

### Pager stays alive; “home” backgrounds it
`RearHost.goHome()` **does not finish** the pager. It puts stock rear home on top and leaves `RearHostActivity` alive in the background, so media/noti/charging can bring it back instantly without a cold start.

- Feature active (media/noti/charging) → auto-return to pager after **4s** (`HOME_RECOVERY_DELAY_MS`).
- No feature active → stay on stock rear home.
- Notification timeout with no other feature → background pager to stock home.
- Wake-on-lock wakes display 1, then `RearHost.bringPagerToFrontIfActive()` brings the pager front if any feature is active (instead of leaving stock home).

### Feature rules
- **Media** is driven by `MediaSessionManager` active sessions (control center), not lockscreen notifications. Notifications from the currently active media package are skipped so a lockscreen media notification never pops a regular noti. Paused media stays visible, exactly like the control center; only `STOPPED`/`NONE` hide it.
- **Notification** shows via `NotificationView`, starts its intro animation only when the pager is actually visible (hidden pager is brought front first, then `onResume` binds the stack). Timeout removes it; if nothing else is running the pager is backgrounded to stock home.
- **Charging** is driven by `ChargingService`: `POWER_CONNECTED` → debounce → `RearHost.show(CHARGING)`. The animation renders inside `ChargingView` and only replays once per plug-in; the wake loop updates the battery level every ~2s without restarting the fill animation. The service is only started from the MRSS app toggle/startup, so if the app has not been opened the charging service is not running.

### Page selection and dots
`RearPagerState.decidePage(notiActive, chargingActive, mediaActive)` returns `NOTIFICATION > CHARGING > MEDIA`, else the last user page. If a feature is active and the user swipes elsewhere, `RearPagerView` waits `AUTO_RETURN_DELAY_MS` (4s) before switching back.

Pagination dots visibility is a Flutter setting (`pager_dots_visible`, **default off**). Native reads it from `FlutterSharedPreferences` key `flutter.pager_dots_visible`; `RearPagerView.applyDotsVisibility()` applies it on construction, and `RearHostActivity.setDotsVisible()` updates a live host immediately.

## Useful diagnostics

- `adb shell am stack list | grep RearHostActivity` — check the rear Activity is frontmost and its `visible=`/`taskId`.
- `adb shell dumpsys display` — display 0 = main, display 1 = rear (physical uniqueId `local:4630946457447247252`).
- `adb shell dumpsys SurfaceFlinger --display-id`, then `adb shell screencap -p -d <physical-id> /sdcard/x.png && adb pull /sdcard/x.png` — screenshot the rear display. `screencap -d 1` does **not** work; use the physical display ID.
- `adb shell dumpsys media_session` / `dumpsys notification --noredact` — inspect active MediaSessions / raw notification flags.
- Native `Log.d`/`Log.w` frequently does not surface on this hardware. Write a breadcrumb file instead (`new File(context.getFilesDir(), "x.log")`, append-only) and read it back with `adb shell run-as com.tgwgroup.MiRearScreenSwitcher cat files/x.log`.

## HyperOS platform quirks (don’t relitigate)

- **`ActivityStarterImpl` rejects rear-display launches more often than “locked screen.”** A fresh `am start --display 1 ...` can be denied when locked, and bringing an existing backgrounded `singleInstance` task back to front with `--display 1` is treated as a new-task launch and rejected. Fix: drop `--display 1` when the task already exists on display 1; when there is genuinely no existing task and the launch is denied, launch an invisible placeholder on the main display, poll `am stack list` for its `taskId`, then `am display move-stack <taskId> 1`.
- **`ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` cannot be received by manifest-declared receivers.** Only a dynamically registered receiver from a persistent component (`NotificationService.wakeOnLockReceiver`) receives them. Do not extend `RearScreenBroadcastReceiver` for these two actions.
- **Rear display wake is not “bring MRSS front.”** `input -d 1 keyevent KEYCODE_WAKEUP` wakes the screen but can reveal stock rear home. If a feature is active, also call `RearHost.bringPagerToFrontIfActive()`.
- **Waking a dozing secondary display** requires `input -d 1 keyevent KEYCODE_WAKEUP`; plain `input keyevent KEYCODE_WAKEUP` wakes both displays.
- **Swiping a rear Activity away** calls `onStop()`/`onPause()` with `isFinishing()==false`; the task survives in the background. The pager relies on this to stay alive behind stock home.
- **`MediaController.Callback` fires bursts** of `onMetadataChanged`/`onPlaybackStateChanged` within milliseconds. Debounce before reacting synchronously (see `NotificationService.scheduleShowMediaOnRearScreen`).
- **Not every active MediaSession is meaningful.** Some apps keep a `PlaybackState == null` placeholder registered. Skip null-state sessions entirely rather than falling back to “the first one.”
- **`FLAG_ONGOING_EVENT` is not a reliable media-notification signal.** Some apps’ now-playing notifications don’t set it. Check `Notification.EXTRA_MEDIA_SESSION` and the active media package instead.

## Two separate SharedPreferences files

`mrss_settings` (native-only settings, read/written directly by Java services) and `FlutterSharedPreferences` (the Flutter `shared_preferences` plugin’s own file, keys prefixed `flutter.`). Some flags are bridged manually between the two (e.g. `NotificationService` reads `flutter.notification_service_enabled` directly). Check which file a given setting lives in before assuming.

## Quick Settings tiles and URI control

`SwitchToRearTileService`, `RearScreenshotTileService`, `RearScreenRecordTileService`, `ReturnToMainTileService` are independent QS tiles. `UriReceiverActivity`/`UriCommandService` implement the `mrss://` scheme for external control (Tasker/MacroDroid).
