<p align="center">
  <img src="docs/icon.svg" width="96" alt="Altrove icon">
</p>

<h1 align="center">Altrove</h1>

<p align="center">
  <em>Be somewhere else, five minutes at a time.</em><br>
  An Android home-screen widget that moves your GPS position to a spot you choose
  for exactly five minutes, then puts the real one back on its own.
</p>

<p align="center">
  <a href="https://github.com/Perruzz/altrove/releases/latest/download/Altrove.apk"><strong>⬇ Download Altrove.apk</strong></a>
</p>

<p align="center">
  <img src="docs/screenshots/widget_idle.png" width="360" alt="The widget at rest, showing the destination">
  <img src="docs/screenshots/widget_active.png" width="360" alt="The widget during a session, counting down">
</p>

<p align="center">
  <img src="docs/screenshots/app_idle.png" width="240" alt="The app: latitude and longitude fields">
  <img src="docs/screenshots/app_active.png" width="240" alt="The app during a session">
</p>

*Altrove* is Italian for *elsewhere*.

## What it does

- **The app** has two fields, latitude and longitude. Type them in (or paste a pair copied
  from Google Maps) and save.
- **The widget** is the switch. Tap it and, for the next five minutes, every app on the phone
  that asks where you are gets the saved coordinates: open Google Maps and the blue dot is
  there. Nothing is blocked or paused, the phone just believes it is elsewhere.
- **After five minutes** the real GPS comes back by itself. Tap the widget again to end
  earlier.

While a session runs, the widget turns to the accent colour and counts down, and a
notification shows the same timer with a Stop button (on recent Android versions the countdown
also sits in the status bar). The colours follow your wallpaper (Material You), in light and
dark theme. The interface is in English and Italian.

## Download

**[Altrove.apk](https://github.com/Perruzz/altrove/releases/latest/download/Altrove.apk)**
(about 1.3 MB), always the latest version. Older builds are on the
[releases page](https://github.com/Perruzz/altrove/releases).

Requires Android 8.0 or later. It is developed and tested on a Pixel 8a with Android 17 and on
an Android 16 emulator; older versions are supported by the code but untested.

Open the file on the phone to install it. Android will ask you to allow installs from the app
you opened it with (browser or file manager): that is expected for apps from outside the Play
Store.

## Setup

Done once, in the app, which walks you through it.

1. **Pick Altrove as the mock location app.** Android only lets one app, chosen by you in
   *Developer options*, replace the device position. Tap **Open Developer options** in the
   app, then **Select mock location app → Altrove**.
   If the phone has no Developer options yet, enable them first: *Settings → About phone*,
   tap *Build number* seven times.
2. **Optional permissions.** *Notifications* show the countdown. *Location* lets Google Play
   services hold the spot steadily in Google's own apps (see [How it works](#how-it-works));
   Altrove itself never reads your real position.
3. **Add the widget.** Tap **Add the widget to the home screen** in the app, or long-press the
   home screen and pick it from the widget list.

## Use

1. Open Altrove, enter latitude and longitude in decimal degrees (for example `41.8902` and
   `12.4922`), tap **Save**. Pasting `41.8902, 12.4922` into either field fills both.
2. Tap the widget. You are there for five minutes.
3. Tap it again to come back early, or let the timer run out.

Saving new coordinates during a session moves you there at once, without resetting the timer.

## How it works

Altrove uses the mock-location API that Android provides for testing, nothing hidden and no
root.

- Tapping the widget starts a foreground service. It replaces the `gps`, `network` and `fused`
  location providers with test providers and reports the saved coordinates on each of them
  once a second.
- If the location permission was granted, it also puts Google Play services' fused provider
  in mock mode. Without this, Play services may blend the mocked GPS with real Wi-Fi and cell
  fixes, and apps that rely on it can drift back to where you really are.
- Five minutes later, to the second, it removes the test providers and stops. A wake lock
  keeps the timer exact with the screen off (measured on a Pixel 8a: 299.98 s).
- If the app dies mid-session, the leftovers are removed as soon as it runs again, so the real
  GPS never stays switched off. Android restarts it by itself after a crash.

The app has no internet permission: it cannot send anything anywhere.

## Good to know

- **Apps can tell.** Android marks every mocked position as mocked, and that flag cannot be
  removed without root. Maps, weather and most apps ignore it, but apps that check it (some
  banking apps and games, for instance) will know.
- **Force stop.** If you force-stop Altrove from Android's settings during a session, tap the
  widget or open the app once: that restores the real GPS.
- **One mock app at a time.** Choosing another mock location app in Developer options turns
  Altrove off until you select it again.

## Build from source

Needs JDK 17 or newer and the Android SDK (platform 37).

```bash
git clone https://github.com/Perruzz/altrove.git
```

```bash
cd altrove && ./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Without signing configuration it is signed
with the debug key, which is fine for your own phone. To use your own key, create
`keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The code is Kotlin: Jetpack Compose for the app screen, a classic `RemoteViews` widget, and
one foreground service. Start reading from
[`MockLocationService.kt`](app/src/main/java/app/altrove/MockLocationService.kt) and
[`LocationMocker.kt`](app/src/main/java/app/altrove/LocationMocker.kt).
