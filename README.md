# Helm

An Android home-screen launcher that brings Nothing OS 5's look and feel to phones that aren't Nothing phones.
It was built for, and on, a Samsung Galaxy S25 running One UI.

> **How this was made:** Helm was written by **Claude Opus 5.5** (Anthropic), working in Claude Code.
> A human directed it, installed each build on their phone and reported what was broken.
> All 52 Java files (about 14,700 lines), the build script and these docs are the model's output.
> No Nothing code was decompiled or copied. The design comes from Nothing's public posts and design boards, the official changelog and press coverage.
> The notes are in [`docs/nothing-os-5-research.md`](docs/nothing-os-5-research.md).

Unofficial. Not affiliated with, or endorsed by, Nothing Technology.

## Status, frankly

- **Versions 1 and 2** were installed and used on a Galaxy S25. Bugs found there were fixed.
- **Version 3.0 (this code)** adds the quick panel, recent apps, lock-screen clocks and several widgets, and rewrites parts of the panel and gestures.
  - It compiles cleanly, and AI review passes read through it for crash risks.
  - It **has not been run on a phone yet**. Expect rough edges, and please open an issue with what you see.
- There are no automated tests. Every test so far was a person tapping on a phone.

## What it does

**Home screen**
- **Two themes, as in NOS 5.** Translucent has frosted surfaces over the wallpaper. Classic is opaque, monochrome and red.
- **Colour engine.** Picks an accent from the wallpaper and uses it on toggles, the clock's second hand and dots.
- **Wallpapers.** Generated "Atmosphere" wallpapers (grain, hairline circles, dot grid), your own photo, a solid colour or the phone's wallpaper.
- **Icons.** Monochrome themed icons, icon packs, circle/squircle/rounded shapes and label options.
- **Big icons.** Any app can be 2×2. So can folders.
- **Editing.** Arrange mode moves several apps and widgets at once. Notification dots on icons.
- **Fonts.** Geist, Geist Mono and Instrument Serif. Dot-matrix type is kept for data and clocks.

**Widgets** (Helm draws its own, in Nothing's shapes: 1×1 circles, 2×1 pills, deep-radius tiles)
- **Clocks:**
  - Analog, in 5 faces.
  - Dot clock, in 4 typefaces.
  - World clock.
  - Date.
  - Calendar month.
- **Everyday:**
  - Weather (Open-Meteo, no account).
  - Battery.
  - Music, with controls, in 3 styles.
  - Quick toggles.
  - Agenda.
  - Note.
  - Photo (frame or sticker).
- **Wellbeing:**
  - Away Time.
  - Screen time.
  - Steps.
- **Utility:**
  - Countdown.
  - Timer.
  - Compass.
  - Quick dial.
- It also hosts normal Android widgets.

**App drawer**
- Search across apps, a calculator, settings shortcuts and the web.
- Suggestions and category chips.
- Drawer groups: folders inside the drawer, made by hand or grouped by category.
- Hidden apps behind fingerprint.

**Quick panel and recent apps** (Helm's own, in Nothing's style)
- **Quick panel.** Swipe down on home and it follows your finger. It has:
  - the time in dots,
  - Internet and Bluetooth pills,
  - round tiles you pick,
  - a brightness bar,
  - a music card,
  - a button for the real notification shade.
- **Recent apps.** Tall cards built from usage history. Swipe a card up to remove it.

**Lock screen**
- A live wallpaper draws a ticking clock and date while the phone is locked.
- Clock styles: Dots, Stacked, Serif, Analog, Gooey and Terminal.
- The clock colour can match the wallpaper.

## What Android doesn't allow (and what Helm does instead)

- **Notification shade, recents screen and lock screen.** On Samsung phones only One UI can draw these. Helm offers its own quick panel and recents, and a live-wallpaper lock clock.
  - One UI still draws its own clock and notifications on top of the lock screen. Choose One UI's smallest clock, or hide it with Good Lock.
- **Long-press Home for recents.** This works only if One UI lets you choose Helm as the "Digital assistant app" (Settings › Apps › Default apps). That hasn't been confirmed on a device yet.
- **Picture-in-picture.** With a third-party launcher and gesture navigation, One UI may not shrink videos into PiP when you swipe home. Pressing a Home button (3-button navigation) should work. No launcher can force PiP on apps that don't support it.
- **No recents screenshots.** Recents cards show icons, not screenshots: third-party launchers can't get them.

## Install

1. Build the APK (below), or use one from Releases if the repo owner publishes it.
2. Sideload it, open **Helm Settings** and tap **Set as default**.
3. Android 13+ greys out some permissions for sideloaded apps. Go to **Settings › Apps › Helm › ⋮ › Allow restricted settings** first.

Everything is optional. Each feature asks for what it needs only when you use it:

| Permission / access | Used for |
|---|---|
| Notification access | Music widget, the panel's player, icon dots. Counts notifications per app; never reads them. |
| Usage access | Recent apps, Away Time, Screen time |
| Modify system settings | Brightness bar and auto-rotate in the quick panel |
| Accessibility service | Lock screen and open-notifications gestures. Reads nothing on screen. |
| Calendar | Agenda widget |
| Physical activity | Steps widget |
| Internet | Weather only (Open-Meteo) |

No analytics or accounts, and nothing is sent anywhere except Open-Meteo weather requests.

## Build from source

No Gradle. `build.sh` calls the Android SDK tools directly (aapt2 → javac → d8 → zipalign → apksigner).

You need:
- JDK 17 or newer,
- an Android SDK with **platform 36** and **build-tools 36.x**,
- bash (Git Bash works on Windows).

```bash
export ANDROID_HOME=/path/to/android-sdk   # or keep an SDK at ./.toolchain/sdk
export JAVA_HOME=/path/to/jdk-17           # or ./.toolchain/jdk17
./build.sh                                 # writes Helm.apk
```

- **Signing key.** On first build the script creates a signing key in `keystore/`. It's git-ignored on purpose.
- **Keep that key.** Android only installs an update over Helm if it's signed with the same key.
- **Password.** Set `HELM_KS_PASS` to your own password for anything you share.

## Layout

```
app/AndroidManifest.xml
app/res/                     fonts, theme, icon, a few XML configs
app/src/com/helm/launcher/
  HelmActivity.java          home: pages, dock, gestures, drawer, folders, editing
  HomeRoot.java              home's touch handling (swipes, long-press, drag layer)
  DrawerView.java            app drawer, search, groups
  Shade.java, Overlay.java   quick panel and recent apps (in home, and over other apps)
  HelmWidget.java            widget base; Widgets / MoreWidgets / ExtraWidgets hold the set
  Palette, Material, WallpaperStore, Atmosphere   colour engine, frosted materials, wallpapers
  LockArt, HelmWallpaper     lock-screen clock (live wallpaper)
  SettingsActivity.java      Helm Settings
docs/nothing-os-5-research.md   what was learned about NOS 5 and how
licenses/OFL-fonts.txt          font licence
```

## Credits and licences

- [Geist and Geist Mono](https://github.com/vercel/geist-font), and [Instrument Serif](https://github.com/Instrument/instrument-serif). All three are under the SIL Open Font License 1.1; see [`licenses/OFL-fonts.txt`](licenses/OFL-fonts.txt).
- Weather data by [Open-Meteo](https://open-meteo.com).
- Dot-matrix glyphs, icons and generated wallpapers are drawn in code by Helm.
- "Nothing", "Nothing OS" and "Glyph" are Nothing Technology's trademarks. They're used here only to describe the style.

**Licence for Helm's own code:** none chosen yet, so by default all rights are reserved by the repo owner. If you want others to use or contribute, add a `LICENSE` file (MIT and Apache-2.0 are common choices).
