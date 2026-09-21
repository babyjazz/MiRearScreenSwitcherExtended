# Known issue: charging animation gets killed ~1.4s after showing

**Status:** confirmed, reproducible, not fixed. Root cause understood at a "what triggers it" level, but the real fix requires restructuring the launch flow, which was deliberately deferred.

## Symptom

The rear-screen charging animation is supposed to stay visible for 8 seconds (or indefinitely in "always-on" mode). In practice it shows correctly, then gets force-closed after roughly **1.37–1.4 seconds** — every single time, on real physical charger plug/unplug tests. Timing was remarkably consistent across multiple independent tests:

- 1.375s
- 1.365s
- 1.428s
- 1.389s
- 1.372s

This consistency strongly suggests a fixed OS-level timeout/watchdog, not a race condition or random flakiness.

## Evidence

Confirmed via `adb logcat`, filtered to the app's own PID, on a real (non-simulated) charger plug/unplug:

```
16:40:52.772  RearScreenChargingActivity: onCreate完成 (animation starts, 8s auto-close scheduled)
16:40:52.774  RearScreenChargingActivity: 🟢 onResume
...
16:40:54.163  RearScreenChargingActivity: 🔴 onDestroy被调用   <- only 1.39s later, not 8s
```

And in the full (unfiltered) system log, right before each premature destroy:

```
D MiuiFreeFormGestureController: deliverResultForFinishActivity resultTo: null
  resultFrom: ActivityRecord{... com.tgwgroup.MiRearScreenSwitcher/.RearScreenChargingActivity ...}
  isInVideoOrGameScene: false
  intent: Intent { flg=0x10800000 xflg=0x4 cmp=.../.RearScreenChargingActivity (has extras) }
```

No broadcast from our own code (`FINISH_CHARGING_ANIMATION` / `INTERRUPT_CHARGING_ANIMATION`) preceded it — this is the OS itself finishing the activity, not our own logic.

## What this is NOT

Ruled out during investigation:

- **Not a simulation artifact.** Initially found via `adb shell dumpsys battery set ac 1` (used to test without physically unplugging the USB/adb cable). Confirmed to reproduce identically on a real physical charger plug/unplug.
- **Not caused by `android:resizeableActivity`.** Tried setting it to `false` on `RearScreenChargingActivity` (hypothesis: opting out of MIUI's Freeform/multi-window handling). No effect — same kill, same timing.
- **Not caused by any of MRSS's own background services.** `RearScreenKeeperService`, `NotificationService`, etc. logged nothing in the relevant time window. The kill is not something our own code is triggering indirectly.
- **Not fixable by launching directly on the rear display.** Tried `am start --display 1 -n .../.RearScreenChargingActivity` directly, bypassing the "launch on display 0, then move" workaround entirely. Result: HyperOS **aborts the launch outright** —
  ```
  ActivityStarterImpl: aborted activity = ... show on rear display
  ```
  This confirms the existing "launch on main display, then move to rear display" workaround is *mandatory* on this OS build, not a stylistic choice — direct rear-display launch is blocked at the OS level for third-party apps.

## Working theory

`SwitchToRearTileService` (the app's core feature — moving an already-running app like YouTube to the rear screen) uses the exact same underlying move mechanism as `ChargingService`:

```java
taskService.moveTaskToDisplay(taskId, 1);  // -> "am display move-stack <taskId> 1"
```

...and does **not** get killed by `MiuiFreeFormGestureController`. The difference is *what* gets moved:

- `SwitchToRearTileService` moves an **existing, already-running** app's task — one that's been alive for a while, already fully initialized, already interacted with by the user.
- `ChargingService` (and `NotificationService`) **create a brand-new task via `am start`, then move it to another display within ~150ms of creation.**

Working theory: HyperOS's `MiuiFreeFormGestureController` treats "a freshly-created task immediately relocated to another display" as suspicious — possibly matching some internal freeform/floating-window preview gesture that expects an acknowledgment from the app within ~1.4s, or a heuristic against scripted/automated cross-display task movement. An "old", stable task moved the same way is trusted and left alone.

This is a theory based on behavioral evidence, not on decompiled HyperOS source — it explains the observed asymmetry well, but hasn't been independently verified against Xiaomi's actual implementation.

## Why a real fix is bigger than a patch

If the theory is correct, the fix isn't a flag or a manifest tweak — it's a structural change to *how* the charging (and notification) animation gets shown:

- Stop creating a fresh `RearScreenChargingActivity` task via `am start` for every charge event.
- Instead, pre-create/park the activity's task once (e.g. at app startup or on first use), and for every subsequent charge event, reuse that existing task and update its content via `onNewIntent` (already implemented as of today's other fixes) instead of a new `am start` + move.
- This means the task is always "old" from HyperOS's perspective by the time any move/re-show happens — matching the pattern that `SwitchToRearTileService` uses successfully.

Complications this introduces that need real design work, not just a quick patch:

- Where does the parked task live when not showing an animation? It can't just sit visibly on the rear display forever (would visually conflict with the Xiaomi launcher / a genuinely projected app).
- Need a reliable way to move it *back* to a non-intrusive state after each animation without re-triggering the same "just moved, looks suspicious" pattern on the way back.
- Interaction with the existing `RearAnimationManager` interrupt logic (charging vs notification animations already fight over the rear screen) — a persistent task changes the assumptions that logic was built on.
- Needs the same treatment for `RearScreenNotificationActivity`, which likely has the identical problem (not yet confirmed with a real-hardware test — `cmd notification post` from shell didn't trigger MRSS's listener, so this wasn't independently verified for notifications specifically).
- Real on-device testing required across multiple scenarios (locked screen, always-on mode, notification-interrupts-charging, rapid repeated triggers) since the change touches the core lifecycle of these activities.

Given all of the above, this was deliberately deferred rather than attempted same-day alongside the other fixes.

## Related context

This issue is independent of, and was found after, today's other fixes:
- The `service call activity_task 50` → `am display move-stack` migration (Android 16 / HyperOS 3.0 compatibility — the old command's transaction code no longer maps correctly).
- The `onNewIntent()` fix for `singleInstance` activity reuse.
- The cooldown-timestamp fix in `ChargingService`.
- The HyperOS battery-restriction auto-reset issue (separate bug, unrelated to this one, already fixed by re-enabling "No restrictions" in system settings).
- Four battery-drain/memory-leak fixes (30ms reconnect loop typo, 100ms wakeup-loop shell-exec spam, WakeLock timeout, stale `currentInstance` references).

None of those fixes caused or fixed this issue — it appears to be a pre-existing HyperOS 3.0 behavior that was simply never isolated before today's deep logcat-based investigation.
