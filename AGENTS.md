# AGENTS.md

Instructions for AI coding agents (Claude Code, Codex, Gemini CLI, Cursor and others) working with this
repository. People should start with [README.md](README.md); this file has the same facts in the form an
agent needs.

## Which job are you doing?

| You were asked to | Do this |
|---|---|
| Install TV Photos on someone's TV, or update it | [Installing on a TV](#installing-on-a-tv), on the real TV. Change no code. |
| Change the app: a fix, a feature, docs | [Repo map](#repo-map) and [Changing the code](#changing-the-code). |

## Installing on a TV

### Rules

- **Touch only this app** (`home.tvphotos`) and the three screensaver settings that `install.sh` sets. Do not
  uninstall, disable or change any other app or setting on the TV. No root.
- **Ask the person first** before you install anything on their computer (JDK, Android SDK,
  platform-tools), and before you replace their current screensaver. Tell them what it is now.
- **Never uninstall TV Photos** to get past an install error without the person's clear OK: uninstalling
  deletes every photo on the TV. "INSTALL_FAILED_UPDATE_INCOMPATIBLE" means the copy on the TV was built on
  another computer (another signing key); explain that, and let them choose.
- **The PIN is the person's.** Do not read, guess, print or type it. They see it on the TV after
  *New random PIN*.
- **Check with screenshots** and look at them: `adb exec-out screencap -p > shot.png`. A key you inject is
  not proof that the remote works; ask the person to press the real button.
- **Stop and say so** if the TV runs Android below 8.0 (API 26) or the install is refused. Do not lower
  `minSdk` and do not hand over an APK built somewhere else.

### Steps

1. **Tools on the computer.** `adb version`. To build: `java -version` (17 or newer) and an Android SDK with
   platform 34 and build-tools (`ANDROID_HOME`, or `sdk.dir` in `local.properties`). If something is missing,
   say what and ask before installing it. On macOS with Homebrew, for example:
   `brew install --cask temurin@17 android-commandlinetools`, then
   `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"` and
   `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
2. **The TV.** The person switches on USB debugging (Settings > System > About > press OK on *Build* 7 times;
   then Developer options > *USB debugging*) and gives you the TV's IP address. Guide them; you cannot do
   this part.
3. **The current screensaver.** `adb connect <ip>:5555`, then
   `adb -s <ip>:5555 shell settings get secure screensaver_components`. Tell the person what it is, so they
   can go back to it, and ask before you replace it.
4. **Install.** `./install.sh <ip>`. It connects, waits while the person allows debugging on the TV, checks
   the Android version, builds, installs (keeping photos on an update), opens the app and sets the
   screensaver.
5. **Check.**
   - A screenshot shows TV Photos with the page address.
   - `adb -s <ip>:5555 shell settings get secure screensaver_components` prints `home.tvphotos/.PhotoDream`.
   - `curl -s -o /dev/null -w '%{http_code}' http://<ip>:8080/` prints `200`, and
     `curl -s -o /dev/null -w '%{http_code}' http://<ip>:8080/api/photos` prints `401` (the PIN guards it).
   - Start the screensaver: `adb -s <ip>:5555 shell am start -n com.android.systemui/.Somnambulator`, take a
     screenshot (with no photos yet it shows "No photos yet" and the address), then
     `adb -s <ip>:5555 shell input keyevent BACK`.
6. **Hand over.** Tell the person to choose *New random PIN* on the TV, open the address on their phone and
   add photos, and how to set the town for the weather. Then ask whether to switch USB debugging off: port
   5555 is plain text on their network while it is on.

### Undo

- Back to another screensaver: TV settings, or on Google TV
  `adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop`.
- Remove the app (deletes its photos, ask first): `adb uninstall home.tvphotos`.

## Repo map

| Path | What it is |
|---|---|
| `install.sh` | Build, install over ADB, set as screensaver. Usage in its first lines. |
| `app/src/main/java/home/tvphotos/` | The app: `PhotoServer` (HTTP page server), `Store` (photos, PIN, settings), `Library` (framings, hidden, combinations, playlists in `library.json`), `PhotoDream` + `SlideView` (screensaver), `Framing` (placement maths), `Playing` (playlist filter), `ManageActivity` (the page on the TV), `MainActivity` (the app's screen), `Weather` (Open-Meteo), `Qr`. |
| `app/src/main/java/io/nayuki/qrcodegen/` | Project Nayuki's QR library (MIT), unchanged. Do not edit. |
| `app/src/main/assets/index.html` | The whole page for the phone and, with `?tv=1`, for the TV. |
| `app/src/test/` | JUnit tests for `Framing` and `Playing`. |
| `docs/specs/`, `docs/plans/` | Design notes and plans of the larger features. |
| `docs/images/` | README screenshots, made with drawn demo pictures only. |

## Changing the code

- **Build and test:** `./gradlew testReleaseUnitTest assembleRelease` (JDK 17+, Android SDK 34). Add a test
  for any change to `Framing`, `Playing` or other pure logic.
- **Check on a real TV** when you change the screensaver, the TV app or the page: `./install.sh <ip>` and
  screenshots. Look at the page at phone size (375 px wide) and at the TV size (`?tv=1`, 960x540 CSS px)
  using only the arrow keys, OK (Enter) and Back.
- **Keep `Framing.java` and its JavaScript copy in `index.html` the same.** The editor on the phone must show
  exactly what the TV draws. The test values in `FramingTest` must also hold for the JS copy.
- **No new dependencies:** plain Java and the Android SDK, no AndroidX, no web libraries. `minSdk` stays 26.
- **The page stays one file** with no outside resources: it is served by the TV on a home network that may
  have no internet.
- **Privacy:** the only outside connection is Open-Meteo for the weather. No analytics, no tracking, no other
  network calls. Photos never leave the TV.
- **Security:** every `/api/` route except login is behind the PIN cookie. Validate names (`Store.validName`),
  ids and numbers on the server. The WebView in `ManageActivity` stays locked to `http://127.0.0.1:8080`.
- **Data compatibility:** an update must keep existing photos, `library.json` and settings. Bump
  `versionCode` in `app/build.gradle` for every release.
- **No prebuilt APKs or keystores** in the repo; `.gitignore` keeps `*.apk`, `*.jks` and `*.keystore` out.
- **No personal data** in commits or screenshots: no real photos of people, IP addresses other than
  examples like `192.168.1.50`, PINs, Wi-Fi names or home towns.
