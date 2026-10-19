# MRSS Rear Pager — Design

Date: 2026-10-14
Status: Approved in chat (feature scope), pending written-spec review

## 1. Objective

Replace the current rear-screen model — three separate full-screen views swapped
by `RearStack` / `RearHostActivity` (`MediaView`, `NotificationView`,
`ChargingView`) — with **one host Activity that renders one horizontal pager**.
Each MRSS feature owns one page of that pager. Feature switches become page
switches. This removes the display-ownership racing that comes from swapping
full-screen experiences via `am start` / `move-stack` / interrupt broadcasts.

The stock Xiaomi rear panel (swipe-down drawer with calling/timer pages) is
**not** recreated. Calling and timer stay where they live today: native
HyperOS UI. MRSS pages only cover the three features MRSS already provides.

## 2. Scope

In scope:
- One rear screen: a horizontal pager with exactly three pages, in order:
  1. Music
  2. Notification
  3. Charging
- Each page always exists and shows an empty state when its feature is
  inactive.
- One host task owns display 1; feature activation auto-switches the pager
  page, never launches a separate Activity.
- Page selection priority: `NOTIFICATION > CHARGING > MEDIA` for
  auto-switching; user can still swipe anywhere.
- Wake/lock behavior preserved from current architecture, with last-page
  restore.
- Reuse existing view payloads and layouts where possible.

Out of scope:
- No custom/user page.
- No calling page.
- No timer page.
- No rebuilding the stock swipe-down panel.
- No change to main-display settings UI.

## 3. Current architecture (context)

References:
- `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearHostActivity.java`
  — single `Activity`, renders whichever `RearView` `RearStack` places on top.
- `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearHost.java`
  — entry point: `show()` / `hide()` put entries on `RearStack`, launch host
  on display 1, restore rear when stack empties.
- `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearStack.java`
  — priority stack: `NOTIFICATION > CHARGING > MEDIA`, one payload per type.
- `RearView.java` — interface: `getView()`, `bind(Bundle)`, `unbind()`.
- `MediaView.java`, `NotificationView.java`, `ChargingView.java` — current
  full-screen implementations.
- `ChargingService.java`, `NotificationService.java` — feature drivers that
  call `RearHost.show/hide`.

At the time of writing, `RearHostActivity.render()` swaps the current
`RearView` inside a `FrameLayout` (`rootFrame`). The design replaces that swap
with pager page-switching while keeping the same data payloads.

## 4. Architecture

### 4.1 One host, one pager

`RearHostActivity` hosts a single new `RearPagerView` (implementing
`RearView`) as its content. `RearPagerView` owns:

- Horizontal pager container (`HorizontalScrollView` with snap, or AndroidX
  `ViewPager` if already/cheaply available — no new dependency preferred).
- Three page containers: Music, Notification, Charging.
- Page dots/indicator.
- Page auto-switch entry point consumed by callers that today call
  `RearHost.show(ctx, type, payload)`.

No separate rear Activities. The `STOCK_LAUNCHER` restore path on teardown
stays, but only the one host task is involved.

### 4.2 `RearStack` semantics change

Today `RearStack` says "this is the display owner". With the pager:

- `RearStack.put(type, payload)` still stores each feature's latest payload,
  but no longer means "display overlay takeover".
- A new lightweight "page controller" decides which page is frontmost:
  - user swipes anywhere, anytime;
  - feature activation auto-jumps to that feature's page;
  - after a transient feature ends (notification dismissed, charger
    unplugged) the pager returns to the last user page;
  - priority tie-break remains `NOTIFICATION > CHARGING > MEDIA`.

Recommended minimal change: keep `RearStack` data structures, add a
`RearPagerState` (a small static/persistent holder inside `RearHostActivity`
or a new class) that stores `lastUserPageIndex` and active feature bits.

### 4.3 Page switching rules

Auto-switch events:

| Event | Action |
|---|---|
| Media session starts / resumes | jump to Music page |
| Playback stops, no media | stay or return to last user page if user was elsewhere |
| NotificationListener receives notification | jump to Notification page, show card, start dismiss timer (existing duration pref) |
| Notification dismissed / empty | return to last user page |
| Charger connected | jump to Charging page (always-on) |
| Charger disconnected | return to last user page |

Manual swipe always wins over auto-switch unless a higher-priority feature
fires. Example: user on Music page, notification arrives → noti page; user
dismisses → back to Music.

### 4.4 Page states

Each page renders as compact card inside the pager:

- **Music page** — reuses `MediaView` data (`MediaController` /
  `NotificationService.getActiveMediaController()`): title, artist, album art,
  prev/play/next. Empty state: "No music playing now".
- **Notification page** — reuses `NotificationView` data: app icon, app name,
  title, text, tap-to-open. Empty state: "No notifications".
- **Charging page** — reuses `ChargingView` data: battery percent, liquid /
  LightningShapeView fill animation. Empty state: "Not charging".

Actual card layouts may be slimmer than the current full-screen layouts, but
must reuse the same payload keys and service wiring.

## 5. Wake / lock / recovery behavior

Preserved from the current single-host architecture, with last-page restore:

- Waking display 1 from DOZE/DOZE_SUSPEND still requires
  `input -d 1 keyevent KEYCODE_WAKEUP` (window flags do not wake a secondary
  display; plain `input keyevent KEYCODE_WAKEUP` wakes both).
- If host task exists on display 1 but is backgrounded, use bare
  `am start -n com.tgwgroup.MiRearScreenSwitcher/...RearHostActivity` (no
  `--display 1`, which HyperOS rejects for an existing task).
- Cold launch on a locked device: use existing main-display placeholder +
  poll `am stack list` for taskId + `am display move-stack <taskId> 1`.
- Host must remember the last page index before sleep (persist in
  `RearStack` payload or SharedPreferences), and `onStart`/`onResume`
  restore that page.
- HyperOS idle+locked may sleep the rear and `remove-task` the host, bringing
  stock `SubScreenLauncher` to front. Recovery = relaunch the single host
  once and land on the saved page. Do not recreate per-feature relaunch loops.

## 6. Files / components

New:
- `RearPagerView.java` — implements `RearView`; hosts the horizontal pager,
  three page containers, indicator, auto-switch entry point.
- Layout resource(s) for the pager shell and page containers.
- `RearPagerState.java` (or equivalent small state holder) — last user page,
  active feature flags, saved-page persistence.

Changed:
- `RearHostActivity.java` — on rear init, `setContentView` pager host;
  `render()` delegates to `RearPagerView` instead of swapping `RearView`s;
  restore saved page on resume.
- `RearHost.java` — `show()` / `hide()` become "update feature state + maybe
  auto-switch page" instead of stack overlay push/pop; keep launch and
  restore-rear helper methods mostly intact.
- `RearStack.java` — becomes a payload holder only (per-type latest payload);
  page ownership moves entirely to `RearPagerState`. No overlay push/pop
  semantics remain.

Kept approximately as-is:
- `MediaView` / `NotificationView` / `ChargingView` payload keys and service
  drivers (`ChargingService`, `NotificationService`).
- `TaskService`, Shizuku bridge, density/cutout helpers.

## 7. Error handling

- If a feature driver calls `show()` before the pager host initialized, store
  the pending payload in `RearPagerState`; apply on host init (mirrors
  existing `RearStack` behavior).
- If rear cannot be woken or host launch fails during Cold start, keep
  existing failure path (log breadcrumb file if needed; do not spin infinite
  relaunch loops).
- Media callback bursts: keep existing debounce patterns
  (`NotificationService.scheduleShowMediaOnRearScreen`); do not react to
  every `onPlaybackStateChanged`.

## 8. Testing plan

No automated test suite exists in this repo. Verification is manual via adb
on a real dual-screen device, after `cd android && ./gradlew assembleDebug -q`
succeeds and `adb install -r ...` succeeds.

Manual checks:
1. Rear shows 3-page pager, swipe left/right, dots follow.
2. Empty states show for inactive features.
3. Music plays → music page shows; stop → empty state.
4. Notification arrives → noti page; dismiss timer → return to last page.
5. Charger plug → charging page; unplug → return to last page.
6. User on music page, notification arrives → noti page, dismiss → back to
   music.
7. Lock rear while on notification page → wake → notification page (or
   saved page after HyperOS remove-task recovery).
8. Idle + locked sleep → wake → saved page (task survives) or MRSS relaunch
   to saved page (HyperOS removed task).
9. No multi-Activity flicker: `adb shell am stack list | grep RearHostActivity`
   shows one task.

## 9. Sequencing

This design directly extends the existing refactor to a single
`RearHostActivity`. Implementation should land as one focused change:
build pager view + page switching, then migrate
`RearHost`/`RearStack`/`RearHostActivity`, then wake/lock restore, then
device testing.

## 10. Open decisions (resolved)

- Custom page: **no**.
- Page order: Music → Notification → Charging.
- Pager is the only rear UI; feature switching = page switching: **yes**.
