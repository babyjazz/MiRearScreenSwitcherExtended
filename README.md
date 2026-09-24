# MiRearScreenSwitcher English (feature fork)

Interactive media playback on the rear display, built on top of the English
MiRear Screen Switcher project for dual-screen Xiaomi devices (e.g.
Xiaomi 17 Pro / 17 Pro Max).

## Read all details

This repository is a feature fork. For the full original project details —
setup, Shizuku wireless debugging, quick settings tiles, rear screenshot /
recording / charging animation / notification mirroring, display tuning,
URI control, permissions, and changelog — please read all detail in:

**https://github.com/aaronnat23/MiRearScreenSwitcherEnglish**

## New feature: Interactive media playback on the rear screen

Feature branch: **`feat/add-media-player`**

- Shows currently-playing media on the rear display: song title, artist, and
  rounded-square album art with a blurred album-art background.
- Works with any app that has an active `MediaSession` (e.g. YouTube Music,
  Spotify).
- Real **prev / play-pause / next** controls wired to that app's
  `MediaController`.
- Ongoing media notifications are excluded from the regular notification
  popup pipeline (detected via `EXTRA_MEDIA_SESSION`, not just
  `FLAG_ONGOING_EVENT`), so metadata updates don't spawn chat-style popups
  that interrupt the media screen.
- A real notification can interrupt media display — and media resumes
  automatically once the notification goes away.
- Auto-recovers if the rear media screen is swiped away while media is still
  active.
- Handles HyperOS rear-display quirks: camera-cutout safe-area padding,
  correct rear-screen density/DPI, and the `move-stack` fallback when a
  locked `--display 1` launch is denied.
- Tuned media layout: compact 70dp rounded-square album art, wide title /
  artist area, and top-positioned clock + controls.

`MEDIA` is registered in `RearAnimationManager` as a full-screen rear
experience, so it coordinates cleanly with charging and notification
animations instead of fighting for the display.

## Requirements / Setup

You still need a Xiaomi dual-screen phone and a running Shizuku service.
Follow the Quick Setup steps in the upstream README linked above, then enable
media playback in the app and play music.

## License

GPL-3.0 (per upstream). Original author: **AntiOblivionis**
(GitHub [GoldenglowSusie](https://github.com/GoldenglowSusie/)).
