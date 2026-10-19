# MRSS Rear Pager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-feature rear full-screen takeovers with one horizontal pager in `RearHostActivity` (pages: Music, Notification, Charging) so feature switching is page switching and no display-ownership race exists.

**Architecture:** One `RearHostActivity` hosts one `RearPagerView` (horizontal pager, three fixed pages with empty states). Services keep calling `RearHost.show/hide(ctx, type, payload)`; `RearHost`/`RearStack` become payload holders, and `RearPagerState` (pure logic) decides which page shows. Wake/lock keeps current Shizuku launch mechanics plus last-page restore.

**Tech Stack:** Native Android Java (no new external dependency except JUnit for pure-logic tests), existing Shizuku bridge (`TaskService`, `ITaskService`), existing `RearView` payloads, existing `DisplayInfoCache`/`RearDisplayHelper` density+cutout helpers.

**Spec:** `docs/superpowers/specs/2026-10-14-rear-pager-design.md`

## Global Constraints

- Page order fixed: Music (index 0), Notification (index 1), Charging (index 2). Do not reorder.
- No custom page, no calling page, no timer page.
- Pager is the only rear UI. Feature switch = page switch. No separate rear Activities.
- Priority for auto-switch: `NOTIFICATION > CHARGING > MEDIA`.
- One host task on display 1. Do not create per-feature relaunch loops.
- Wake rear requires `input -d 1 keyevent KEYCODE_WAKEUP`; never use `--display 1` to bring an existing host task forward.
- Apply rear density + cutout via existing `forceRearScreenDensityBeforeInflate()` / `applySafeAreaPadding()` helpers.
- No new AndroidX `ViewPager` dependency. Use `HorizontalScrollView` + scroll-snap for the pager.
- Repo has no test suite. Only pure-logic tests run under Gradle (`testDebugUnitTest`); all UI verification is manual via adb.

## Review Focus

1. User on Music page, notification arrives → must land on Notification page; after dismiss, back to Music page.
2. User manually swiped to Charging page while not charging; charger unplugged after an auto-jump → must return to last user page, not to page 0.
3. Rear slept while on Notification page; wake → returns Notification page (not page 0).
4. Multiple features active at once (noti + charging + media) → priority shows Notification; after noti dismisses, Charging page (still charging) shows before Media.
5. HyperOS `remove-task` while idle+locked → single host relaunch restores saved page, no infinite relaunch loop.

---

### Task 1: `RearPagerState` pure logic

**Files:**
- Create: `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearPagerState.java`
- Test: `android/app/src/test/java/com/tgwgroup/MiRearScreenSwitcher/RearPagerStateTest.java`
- Modify: `android/app/build.gradle.kts` (add JUnit `testImplementation`)

**Interfaces:**
- Consumes: nothing (pure Java, no Android imports).
- Produces:
  - `public static final int MUSIC = 0;`
  - `public static final int NOTIFICATION = 1;`
  - `public static final int CHARGING = 2;`
  - `public static final int PAGES = 3;`
  - `public int getLastUserPage();`
  - `public void markUserPage(int index);` (clamps to `0..PAGES-1`)
  - `public int decidePage(boolean notificationActive, boolean chargingActive, boolean mediaActive);`
  - `public int saveForSleep();` (returns saved index)
  - `public int restoreAfterWake();`

- [ ] **Step 1: Add JUnit test dependency**

Modify `android/app/build.gradle.kts` dependencies:
```kotlin
testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Write the failing tests**

`android/app/src/test/java/com/tgwgroup/MiRearScreenSwitcher/RearPagerStateTest.java`:

```java
import org.junit.Test;
import static org.junit.Assert.*;

public class RearPagerStateTest {
    @Test public void priorityOrder_NotiAboveChargingAboveMedia() {
        RearPagerState s = new RearPagerState();
        assertTrue(s.decidePage(true, false, false) == RearPagerState.NOTIFICATION);
        assertTrue(s.decidePage(false, true, false) == RearPagerState.CHARGING);
        assertTrue(s.decidePage(false, false, true) == RearPagerState.MUSIC);
        assertTrue(s.decidePage(true, true, true) == RearPagerState.NOTIFICATION);
        assertTrue(s.decidePage(false, true, true) == RearPagerState.CHARGING);
    }

    @Test public void noFeatureKeepsLastUserPage() {
        RearPagerState s = new RearPagerState();
        s.markUserPage(RearPagerState.CHARGING);
        assertEquals(RearPagerState.CHARGING, s.decidePage(false, false, false));
    }

    @Test public void markUserPageClamps() {
        RearPagerState s = new RearPagerState();
        s.markUserPage(99);
        assertEquals(RearPagerState.PAGES - 1, s.decidePage(false, false, false));
        s.markUserPage(-1);
        assertEquals(RearPagerState.MUSIC, s.decidePage(false, false, false));
    }

    @Test public void saveAndRestoreAfterWake() {
        RearPagerState s = new RearPagerState();
        s.markUserPage(RearPagerState.NOTIFICATION);
        s.saveForSleep();
        s.markUserPage(RearPagerState.CHARGING);
        assertEquals(RearPagerState.NOTIFICATION, s.restoreAfterWake());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.tgwgroup.MiRearScreenSwitcher.RearPagerStateTest" -q`
Expected: compile/FAIL, class `RearPagerState` missing.

- [ ] **Step 4: Implement `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearPagerState.java`**

Pure Java class, no Android imports. `decidePage` returns `NOTIFICATION` if noti, else `CHARGING` if charging, else `MUSIC` if media, else `lastUserPage`. `markUserPage` clamps. `saveForSleep` stores current `lastUserPage`; `restoreAfterWake` returns stored value.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.tgwgroup.MiRearScreenSwitcher.RearPagerStateTest" -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/test/java/com/tgwgroup/MiRearScreenSwitcher/RearPagerStateTest.java android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearPagerState.java
git commit -m "feat(rear): add RearPagerState page-selection logic"
```

---

### Task 2: `RearPagerView` UI shell (horizontal pager, 3 pages, dots, empty states)

**Files:**
- Create: `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearPagerView.java`
- Create: `android/app/src/main/res/layout/rear_pager_view.xml`
- Create: `android/app/src/main/res/layout/page_music.xml`
- Create: `android/app/src/main/res/layout/page_notification.xml`
- Create: `android/app/src/main/res/layout/page_charging.xml`

**Interfaces:**
- Consumes: `RearPagerState.MUSIC/NOTIFICATION/CHARGING` constants; `DisplayInfoCache`/`RearDisplayHelper` for safe-area padding; existing `R.drawable` resources used by current views where possible.
- Produces:
  - `public View getView();`
  - `public void bindMusic(Bundle payload);`
  - `public void bindNotification(Bundle payload);`
  - `public void bindCharging(Bundle payload);`
  - `public void setPage(int index, boolean animate);`
  - `public int getCurrentPage();`
  - `public void unbind();`
  - `public void onFeatureChanged(boolean notiActive, boolean chargingActive, boolean mediaActive);`

- [ ] **Step 1: Add the three page layouts + pager shell layout**

`page_music.xml`:
- TextView id `page_music_title` (empty state: "No music playing now")
- TextView id `page_music_sub`
- ImageView id `page_music_art`
- TextViews id `page_music_song` / `page_music_artist`
- Buttons/ImageButtons id `page_music_prev`, `page_music_play`, `page_music_next`

`page_notification.xml`:
- TextView id `page_noti_title` (empty state: "No notifications")
- TextView id `page_noti_sub`
- ImageView id `page_noti_icon`
- TextView id `page_noti_app`, `page_noti_text`, `page_noti_content`

`page_charging.xml`:
- TextView id `page_charge_title` (empty state: "Not charging")
- TextView id `page_charge_sub`
- FrameLayout id `page_charge_liquid`
- TextView id `page_charge_pct`

`rear_pager_view.xml`:
- vertical `LinearLayout` root
- `HorizontalScrollView` id `pager_scroll` (fill parent height, `scrollbars="none"`, children = 3 `FrameLayout`s each `layout_width=match_parent`, `layout_height=match_parent`, `layout_weight=1`? use width `match_parent` and no weight — `HorizontalScrollView` children width must be a concrete px/dp: use `android:layout_width="match_parent"` does not work for horizontal paging children; set each child width to the scroll viewport width via code, or use `android:layout_width="?attr/..."`). Implementer picks concrete paging width via code in `setPage`.
- LinearLayout id `pager_dots` at bottom (three dot `View`s).

- [ ] **Step 2: Write `RearPagerView.java` skeleton that compiles**

Implement `getView()` inflating `rear_pager_view.xml`; store page containers + dot views; implement `setPage(index, animate)` by translating/scrollTo page width; `getCurrentPage()` returns last set index; `bind*` methods write payload fields or switch to empty state; `unbind()` cancels timers/animations; `onFeatureChanged(...)` calls `setPage(state.decidePage(...), true)`.

Empty-state rule per page:
- Music: payload `title`/`artist` present + `isPlaying` true → filled; else "No music playing now".
- Notification: payload `title`/`text` present → filled card; else "No notifications".
- Charging: payload `batteryLevel` present → liquid + percent; else "Not charging".

Reuse existing payload keys from `MediaView`/`NotificationView`/`ChargingView`: `packageName`, `title`, `artist`, `albumArtPath`, `isPlaying`, `text`, `darkMode`, `batteryLevel`.

- [ ] **Step 3: Build to verify compile**

Run: `cd android && ./gradlew assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/layout/page_music.xml android/app/src/main/res/layout/page_notification.xml android/app/src/main/res/layout/page_charging.xml android/app/src/main/res/layout/rear_pager_view.xml android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearPagerView.java
git commit -m "feat(rear): add RearPagerView horizontal pager shell"
```

---

### Task 3: Wire `RearHostActivity` + `RearHost` to the pager

**Files:**
- Modify: `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearHostActivity.java`
- Modify: `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearHost.java`
- Modify: `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearStack.java` (payload holder only; keep `put/get/remove/contains/isEmpty`)

**Interfaces:**
- Consumes: `RearPagerView` from Task 2; `RearPagerState` from Task 1; existing `RearStack`.
- Produces:
  - `RearHostActivity.onFeatureChanged()` — public, safe from any thread, re-binds pages + auto-switches.
  - `RearHostActivity.getPagerView()` — nullable accessor for tests/drivers.
  - `RearStack` keeps payload holder API unchanged.

- [ ] **Step 1: Modify `RearHostActivity`**

- Replace `rootFrame`/`current` swap with one `RearPagerView pagerView`.
- In `initIfOnRear()`: after `forceRearScreenDensityBeforeInflate()`, create `RearPagerView`, `setContentView(pagerView.getView())`.
- Add `public void onFeatureChanged()`: on main looper, `pagerView.bindMusic(RearStack.get(MEDIA))`, `bindNotification(RearStack.get(NOTIFICATION))`, `bindCharging(RearStack.get(CHARGING))`, then `pagerView.onFeatureChanged(...)` with active flags from `RearStack.contains(...)`.
- Delete `render()`'s view-swap switch; `render()` becomes `onFeatureChanged()`.
- `updateBattery(int)` calls `pagerView.bindCharging` with updated payload.

- [ ] **Step 2: Modify `RearHost`**

- In `show(ctx, type, payload)`: keep `RearStack.put(type, payload)`, keep charging-vs-notification one-shot rule, then if host exists call `host.onFeatureChanged()`; if host does not exist, cold-launch same as today.
- In `hide(ctx, type)`: keep `RearStack.remove(type)`; if host exists call `host.onFeatureChanged()`; if stack empty and host exists, keep host alive (pager stays showing empty states) — do **not** `finishHost()` when stack empties.
- Keep `launchHost`/`restoreRear`/`captureProjectedTask`/`scheduleMediaRecovery` launch machinery as-is.

- [ ] **Step 3: Update `RearStack` if needed**

Confirm `RearStack.put/get/remove/contains/isEmpty/top` still compile for callers; if `top()` unused, leave it or delete only if nothing references it. No API changes required for callers.

- [ ] **Step 4: Build to verify compile**

Run: `cd android && ./gradlew assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearHostActivity.java android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearHost.java android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearStack.java
git commit -m "feat(rear): route RearHost feature changes through pager pages"
```

---

### Task 4: Wake/lock last-page restore

**Files:**
- Modify: `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearHostActivity.java`
- Modify: `android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearPagerState.java` (add persistence helpers)

**Interfaces:**
- Consumes: `RearPagerState.saveForSleep()`/`restoreAfterWake()` from Task 1.
- Produces:
  - `RearPagerState.saveForSleep()` also writes `lastUserPage` to `mrss_settings` pref key `rear_pager_last_page` (context passed in or a static setter; implementer picks context-passing style).
  - `RearHostActivity.onStop()` calls save; `onStart()`/`onResume()` restores page.

- [ ] **Step 1: Add persistence to `RearPagerState`**

Add `public int saveForSleep(Context ctx)` and `public int restoreAfterWake(Context ctx)` overloads that persist `lastUserPage` to `ctx.getSharedPreferences("mrss_settings", MODE_PRIVATE)` under key `"rear_pager_last_page"`. Keep the no-arg versions for Task 1 tests.

- [ ] **Step 2: Update `RearHostActivity` lifecycle**

- `onStop()` (when not finishing): call `state.saveForSleep(getApplicationContext())`.
- `onStart()`/`onResume()`: after `initIfOnRear()`, call `pagerView.setPage(state.restoreAfterWake(getApplicationContext()), false)` then `onFeatureChanged()` so active features can re-auto-switch.

- [ ] **Step 3: Build + unit tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.tgwgroup.MiRearScreenSwitcher.RearPagerStateTest" -q`
Expected: PASS.
Run: `cd android && ./gradlew assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearPagerState.java android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/RearHostActivity.java
git commit -m "feat(rear): restore last pager page after wake/lock"
```

---

### Task 5: Device verification (manual, via adb)

**Files:** none (verification only).

**Interfaces:** consumes the built APK from Tasks 2–4.

- [ ] **Step 1: Install debug build on connected device**

Run: `adb devices` → device listed. Then `adb install -r build/app/outputs/apk/debug/app-debug.apk`.
Expected: SUCCESS.

- [ ] **Step 2: Re-grant notification listener**

Run: `adb shell cmd notification allow_listener com.tgwgroup.MiRearScreenSwitcher/com.tgwgroup.MiRearScreenSwitcher.NotificationService`
Expected: no error.

- [ ] **Step 3: Verify 3-page pager renders**

Wake rear (`adb shell input -d 1 keyevent KEYCODE_WAKEUP`), verify rear shows Music page, swipe right/left shows Notification then Charging page, dots follow.
Expected: 3 pages, order Music → Notification → Charging, empty states when features inactive.

- [ ] **Step 4: Verify priority auto-switch**

Play music → music page. Post notification → notification page. Dismiss notification (wait `notification_duration`) → back to music page. Plug charger → charging page. Unplug → back to music page.
Expected: priority + return-to-last-user-page per spec.

- [ ] **Step 5: Verify wake/lock restore**

While on Notification page, lock/sleep rear. Wake rear. Verify pager returns Notification page (or saved page after HyperOS `remove-task` recovery relaunches host once).
Expected: page restores; `adb shell am stack list | grep RearHostActivity` shows one task.

- [ ] **Step 6: Confirm no multi-Activity flicker**

Run: `adb shell am stack list | grep -i rearhostactivity`
Expected: exactly one host task, no `RearScreenMediaActivity`/`RearScreenChargingActivity`/`RearScreenNotificationActivity` (old Activities gone from stack).

- [ ] **Step 7: Commit any debug/code fixes from verification as separate focused commits**

No code change required if all checks pass; report results.
