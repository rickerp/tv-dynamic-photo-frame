#!/usr/bin/env bash
# Build TV Photos, install it on an Android TV / Google TV over ADB, and make it the screensaver.
#
#   ./install.sh 192.168.1.50               build, install, set as screensaver (the TV's IP address)
#   ./install.sh 192.168.1.50 --no-build    install the APK you already built
#   ./install.sh usb                        a TV on a USB cable (or the only device adb sees)
#
# Needs adb (Android platform-tools); building also needs JDK 17+ and the Android SDK (platform 34).
# On the TV: Settings > System > About > press OK on "Build" 7 times, then Settings > System >
# Developer options > USB debugging ON. Accept the "Allow USB debugging?" prompt when it appears.
set -euo pipefail
cd "$(dirname "$0")"

TARGET="${1:-}"
BUILD=1
[ "${2:-}" = "--no-build" ] && BUILD=0
if [ -z "$TARGET" ] || [ "$TARGET" = "-h" ] || [ "$TARGET" = "--help" ]; then sed -n '2,10p' "$0"; exit 1; fi

# adb: on the PATH, or in an Android SDK in one of the usual places.
ADB="$(command -v adb || true)"
for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
         /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
  [ -n "$ADB" ] && break
  [ -n "$d" ] && [ -x "$d/platform-tools/adb" ] && ADB="$d/platform-tools/adb"
done
[ -n "$ADB" ] || { echo "adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools" >&2; exit 1; }

SERIAL=""
if [ "$TARGET" != "usb" ]; then
  case "$TARGET" in *:*) SERIAL="$TARGET" ;; *) SERIAL="$TARGET:5555" ;; esac
fi
tv() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

# Reach the TV. The first time, it asks "Allow USB debugging?" on screen: choose Allow (tick Always allow).
for i in $(seq 1 60); do
  [ -n "$SERIAL" ] && "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true
  [ "$(tv get-state 2>/dev/null || true)" = "device" ] && break
  [ "$i" -eq 1 ] && echo "Waiting for the TV. If it asks \"Allow USB debugging?\", choose Allow."
  sleep 2
done
[ "$(tv get-state 2>/dev/null || true)" = "device" ] || {
  echo "Could not reach the TV over ADB. Is USB debugging on, and is the IP address right?" >&2; exit 1; }

SDK="$(tv shell getprop ro.build.version.sdk | tr -d '\r')"
if [ "${SDK:-0}" -lt 26 ]; then echo "This TV runs Android API $SDK; TV Photos needs Android 8 (API 26) or newer." >&2; exit 1; fi

APK=app/build/outputs/apk/release/app-release.apk
if [ "$BUILD" = 1 ]; then
  if [ ! -f local.properties ]; then
    SDKDIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [ -n "$SDKDIR" ] || { echo "Set ANDROID_HOME to your Android SDK folder (or write sdk.dir=... in local.properties)." >&2; exit 1; }
    echo "sdk.dir=$SDKDIR" > local.properties
  fi
  ./gradlew --no-daemon -q --console=plain assembleRelease
fi
[ -f "$APK" ] || { echo "No $APK: build it first (run without --no-build)." >&2; exit 1; }

echo "Installing on the TV…"
tv install -r "$APK"
# Opening the app starts its phone page; then make it the screensaver.
tv shell am start -n home.tvphotos/.MainActivity >/dev/null
tv shell settings put secure screensaver_components home.tvphotos/.PhotoDream
tv shell settings put secure screensaver_default_component home.tvphotos/.PhotoDream
tv shell settings put secure screensaver_enabled 1

IP="${SERIAL%%:*}"
[ -n "$IP" ] || IP="$(tv shell ip -4 -o addr show wlan0 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | tr -d '\r')"
echo
echo "Done. Screensaver: $(tv shell settings get secure screensaver_components | tr -d '\r')"
echo "On the TV, TV Photos is open: choose New random PIN to see your PIN."
echo "On your phone (same Wi-Fi): http://${IP:-<tv-ip>}:8080"
echo "When you are done, you can switch USB debugging off again on the TV."
