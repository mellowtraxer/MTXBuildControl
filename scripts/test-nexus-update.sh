#!/usr/bin/env bash
set -euo pipefail

package=de.ricci.buildcontrol
previous="${RUNNER_TEMP:?}/nexus-previous.apk"
current=app/build/outputs/apk/debug/app-debug.apk
wrong_key="$RUNNER_TEMP/nexus-test-wrong-key.jks"
wrong_apk="$RUNNER_TEMP/nexus-test-wrong.apk"
trap 'rm -f -- "$wrong_key" "$wrong_apk"' EXIT

adb install "$previous"
adb shell "run-as $package mkdir -p files"
adb shell "run-as $package sh -c 'echo nexus-saved-state > files/nexus-state'"

keytool -genkeypair -keystore "$wrong_key" -storepass android -keypass android \
  -alias nexus-test -dname 'CN=Nexus Test' -keyalg RSA -keysize 2048 -validity 1 -noprompt
"${ANDROID_HOME:?}/build-tools/35.0.0/apksigner" sign --ks "$wrong_key" \
  --ks-pass pass:android --key-pass pass:android --out "$wrong_apk" "$current"

if adb install -r "$wrong_apk" >"$RUNNER_TEMP/nexus-install.log" 2>&1; then
  echo "Android accepted an incompatible signing identity."
  exit 1
fi
grep -F 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' "$RUNNER_TEMP/nexus-install.log"

adb install -r "$current"
test "$(adb shell run-as "$package" cat files/nexus-state | tr -d '\r')" = nexus-saved-state

if adb install -r "$previous" >"$RUNNER_TEMP/nexus-install.log" 2>&1; then
  echo "Android accepted a downgrade."
  exit 1
fi
grep -F 'INSTALL_FAILED_VERSION_DOWNGRADE' "$RUNNER_TEMP/nexus-install.log"

gradle --no-daemon --max-workers=2 :app:connectedDebugAndroidTest
