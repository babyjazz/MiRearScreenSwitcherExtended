# CLAUDE.md

Guidance for Claude Code (and any other coding agent) when working in this repository.

## What this is

MRSS (Mi Rear Screen Switcher) — a Flutter+Android app for Xiaomi dual-screen phones (e.g. Xiaomi 17 Pro). The rear display is owned by one custom pager showing media playback, notifications, and charging animation. Shizuku provides the privileged shell access; the Flutter layer is a thin settings/status UI, and almost all real behavior lives in native Android Java services.

## Build & run

```bash
flutter pub get

flutter build apk --debug
cd android && ./gradlew assembleDebug -q

flutter build apk --release --split-per-abi --target-platform android-arm64

adb install -r build/app/outputs/apk/debug/app-debug.apk
```

`flutter build apk --debug` writes `build/app/outputs/flutter-apk/app-debug.apk`; `./gradlew assembleDebug` writes `build/app/outputs/apk/debug/app-debug.apk`.

`flutter analyze` runs the Dart linter. There is no `test/` directory; verification is manual on a real device via `adb`. The only unit test target:

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.tgwgroup.MiRearScreenSwitcher.RearPagerStateTest"
```

After any install/reinstall or `am force-stop`, re-grant the notification listener or `NotificationService` silently stops receiving notifications:

```bash
adb shell cmd notification allow_listener com.tgwgroup.MiRearScreenSwitcher/com.tgwgroup.MiRearScreenSwitcher.NotificationService
```

## Architecture

### Two halves, one Shizuku bridge
`lib/main.dart` is the entire Flutter app — settings toggles, status display, app-selection screen — talking to native over a `MethodChannel`. `lib/l10n/app_strings.dart` holds three inline locale maps (`en`, `zh-CN`, `zh-TW`) keyed by string id; there is no `.arb`/`gen-l10n` pipeline.

Everything privileged happens in `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/`. `TaskService.java` implements `ITaskService.aidl` and runs as a Shizuku UserService (shell UID). All display-switching, activity-launching, and `input`/`am`/`dumpsys` calls go through `taskService.executeShellCommand(...)` / `executeShellCommandWithResult(...)`.

Each long-lived component (`NotificationService`, `ChargingService`, etc.) binds its **own** `TaskService` instance with a distinct `.processNameSuffix(...)`; they are not shared. Code that needs a `taskService` reference commonly falls back across services (e.g. `NotificationService.getTaskService()` then `ChargingService.getTaskService()`), since whichever one is currently bound varies.

### The rear pager owns display 1
There is **one** rear Activity: `RearHostActivity`, hosting `RearPagerView` (AndroidX `ViewPager`) with three fixed pages:

- **Music (0)** — `MediaView`
- **Notification (1)** — `NotificationView`
- **Charging (2)** — `ChargingView`

Each page embeds the exact current full-screen MRSS view plus an empty-state overlay when that feature is inactive. `RearStack` is the source of truth: one payload per type, priority `NOTIFICATION > CHARGING > MEDIA`.

Services only call `RearHost.show(ctx, type, payload)` / `RearHost.hide(ctx, type)`. They never launch per-feature Activities or send interrupt broadcasts. `show()` either:
- updates pages in-place when the pager is visible,
- brings the alive-but-backgrounded pager front with a bare `am start` (no `--display 1`),
- or cold-launches it (direct `--display 1` when unlocked, otherwise main-display placeholder + `am display move-stack`).

### Pager stays alive
`RearHost.goHome()` does **not** finish the pager. It backgrounds the pager behind stock rear home, so features can bring it back instantly. Behavior:

- Feature active (media/noti/charging) → auto-return after **4s**.
- No feature active → stay on stock rear home.
- Notification timeout with no other feature → background pager to stock home.
- Wake-on-lock wakes display 1, then brings the pager front if a feature is active.

`RearPagerView` also auto-switches back to the active feature after 4s if the user swiped to another page inside the pager.

### Feature rules
- **Media**: driven by `MediaSessionManager` active sessions (control center), not lockscreen notifications. Notifications from the currently active media package are skipped. Paused media stays visible like the control center; only `STOPPED`/`NONE` hides it.
- **Notification**: `NotificationView` starts its intro animation only when the pager is actually visible. When the pager is hidden, `RearHost.show()` brings it front first and `onResume` binds the stack, so the full noti animation plays.
- **Charging**: `ChargingService` handles `POWER_CONNECTED` → debounce → `RearHost.show(CHARGING)`. `ChargingView` replays its fill animation once per plug-in; the wake loop updates battery level every ~2s without restarting the animation. The charging service only runs after the MRSS app has been opened once.

### Pagination dots
Visibility is a Flutter setting (`pager_dots_visible`, **default off**). Native reads `flutter.pager_dots_visible` from `FlutterSharedPreferences`; `RearPagerView.applyDotsVisibility()` applies it on construction, and `RearHostActivity.setDotsVisible()` updates a live host immediately.

## Useful diagnostics

- `adb shell am stack list | grep RearHostActivity` — check frontmost rear task and `visible=`/`taskId`.
- `adb shell dumpsys display` — display 0 = main, display 1 = rear (physical uniqueId `local:4630946457447247252`).
- `adb shell dumpsys SurfaceFlinger --display-id`, then `adb shell screencap -p -d <physical-id> /sdcard/x.png && adb pull /sdcard/x.png` — screenshot the rear display. `screencap -d 1` does not work.
- `adb shell dumpsys media_session` / `dumpsys notification --noredact` — inspect active MediaSessions / raw notification flags.
- Native `Log.d`/`Log.w` frequently does not surface on this hardware. Write a breadcrumb file instead (`new File(context.getFilesDir(), "x.log")`, append-only) and read it back with `adb shell run-as com.tgwgroup.MiRearScreenSwitcher cat files/x.log`.
- Rear timeline: `adb logcat -b events | grep -E "wm_create_activity|wm_finish_activity|screen_toggled|power_screen_broadcast_done"` (`remove-task` = HyperOS removed our task; `screen_toggled` = main screen only).
- Simulating a charger: `adb shell dumpsys battery unplug` / `set ac 1` (always `dumpsys battery reset` afterwards). It also wakes the **main** screen, so locked-state behavior differs from a real charger.

## HyperOS platform quirks (don’t relitigate)

- **`ActivityStarterImpl` rejects rear-display launches more often than “locked screen.”** A fresh `am start --display 1 ...` can be denied when locked, and bringing an existing backgrounded `singleInstance` task back to front with `--display 1` is treated as a new-task launch and rejected. Fix: drop `--display 1` when the task already exists on display 1; when there is genuinely no existing task and the launch is denied, launch an invisible placeholder on the main display, poll `am stack list` for its `taskId`, then `am display move-stack <taskId> 1`.
- **`ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` cannot be received by manifest-declared receivers.** They only reach a dynamically registered receiver from a persistent component (`NotificationService.wakeOnLockReceiver`). Do not extend `RearScreenBroadcastReceiver` for these two actions.
- **Waking the rear display is not bringing MRSS front.** `input -d 1 keyevent KEYCODE_WAKEUP` wakes the screen but can reveal stock rear home. If a feature is active, also call `RearHost.bringPagerToFrontIfActive()`.
- **Waking a dozing secondary display** requires `input -d 1 keyevent KEYCODE_WAKEUP`; plain `input keyevent KEYCODE_WAKEUP` wakes both displays.
- **Swiping a rear Activity away** calls `onStop()`/`onPause()` with `isFinishing()==false`; the task survives in the background. The pager relies on this to stay alive behind stock home.
- **When locked and idle**, a rear `KEYCODE_WAKEUP` only makes the device interactive for ~1s; HyperOS may then sleep it and bring stock launcher front. A wake sent right after `am display move-stack` is too early; send it ~0.5s after the Activity’s `onStart()` (see `RearHostActivity.onStart`).
- **Display 1 `STATE_ON` does not mean the user woke it.** The stock rear lock-screen clock counts as ON. Watchdogs that relaunch on “rear is on” need failure backoff (see `ChargingService` `MAX_FAILED_RELAUNCHES`).
- **`MediaController.Callback` fires bursts** of `onMetadataChanged`/`onPlaybackStateChanged` within milliseconds. Debounce before reacting synchronously (see `NotificationService.scheduleShowMediaOnRearScreen`).
- **Not every active MediaSession is meaningful.** Some apps keep a `PlaybackState == null` placeholder registered. Skip null-state sessions entirely rather than falling back to “the first one.”
- **`FLAG_ONGOING_EVENT` is not a reliable media-notification signal.** Some apps’ now-playing notifications don’t set it. Check `Notification.EXTRA_MEDIA_SESSION` and the active media package instead.

## Two separate SharedPreferences files

`mrss_settings` (native-only settings, read/written directly by Java services) and `FlutterSharedPreferences` (the Flutter `shared_preferences` plugin’s own file, keys prefixed `flutter.`). Some flags are bridged manually between the two. Check which file a given setting lives in before assuming.

## Quick Settings tiles and URI control

`SwitchToRearTileService`, `RearScreenshotTileService`, `RearScreenRecordTileService`, `ReturnToMainTileService` are independent QS tiles. `UriReceiverActivity`/`UriCommandService` implement the `mrss://` scheme for external control (Tasker/MacroDroid).
