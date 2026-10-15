# MiRearScreenSwitcher English (feature fork)

A custom rear pager for dual-screen Xiaomi devices (e.g. Xiaomi 17 Pro): media playback, notifications, and charging animation all live on one swipable rear screen, built on top of the English MiRear Screen Switcher project.

## Read all details

This repository is a feature fork. For the full original project details — setup, Shizuku wireless debugging, quick settings tiles, rear screenshot / recording / charging animation / notification mirroring, display tuning, URI control, permissions, and changelog — please read all detail in:

**https://github.com/aaronnat23/MiRearScreenSwitcherEnglish**

## Features in this fork

### Rear pager
- One rear screen with three horizontally swipable pages: **Music**, **Notification**, **Charging**.
- Each page auto-switches when its feature is active: notification > charging > music.
- Empty-state pages when no feature is active.
- Pagination dots can be hidden/shown from MRSS settings (default: hidden).

### Media playback
- Shows song title, artist, album art, and prev / play-pause / next controls.
- Driven by the Android control-center / active `MediaSession`, not lockscreen notifications.
- Paused media stays visible, exactly like the control-center media card.
- Tested with YouTube Music (and similar apps exposing a real `MediaSession`).

### Notifications
- Selected apps’ notifications appear on the rear pager with a full intro animation.
- Animation starts only when the pager is actually visible, so it never gets cut.
- After the noti timeout, if no media/charging is running the pager is backgrounded and stock rear home shows.

### Charging animation
- Full-screen liquid-fill charging animation renders on the Charging page.
- Plays once per plug-in; later battery updates do not restart the animation.
- Wake loop keeps the pager lit while charging is active.

### Pager stays alive
- The pager task is kept alive in the background even when stock rear home is visible.
- New media/noti/charging brings it front instantly, without a cold start.
- After a manual swipe-up home, an active feature auto-returns to the pager after 4 seconds.

### Wake-on-lock
- When the main screen is locked, the rear display wakes to the pager if a feature is active, instead of showing stock rear home.

## Requirements / Setup

You still need a Xiaomi dual-screen phone and a running Shizuku service.
Follow the Quick Setup steps in the upstream README linked above, then open the MRSS app once to start the notification/charging services.

## Build

```bash
flutter pub get
flutter build apk --debug
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

After install/reinstall, re-grant the notification listener:

```bash
adb shell cmd notification allow_listener com.tgwgroup.MiRearScreenSwitcher/com.tgwgroup.MiRearScreenSwitcher.NotificationService
```

## License

GPL-3.0 (per upstream). Original author: **AntiOblivionis**
(GitHub [GoldenglowSusie](https://github.com/GoldenglowSusie/)).
