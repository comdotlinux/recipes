#!/usr/bin/env bash
# Installs the debug APK on the running emulator and runs the Maestro smoke flow.
# Used by .github/workflows/e2e.yml (android-emulator-runner runs each script line separately,
# so everything lives in this one file) and locally against a booted emulator.
# Env: APP_ID (debug applicationId), E2E_OUT (output directory), ANDROID_SERIAL (target emulator, required),
# E2E_APK (optional APK path; default is the Gradle debug output).
set -euo pipefail

# Maestro ignores ANDROID_SERIAL and picks any running emulator, and the flow clears the app's data,
# so the target device must be named explicitly. emulator-5580 is the owner's manual-testing instance.
device="${ANDROID_SERIAL:-}"
[ -n "$device" ] || { echo "refusing to run: set ANDROID_SERIAL to the target emulator (e.g. emulator-5554)" >&2; exit 1; }
[ "$device" != "emulator-5580" ] || { echo "refusing to run on emulator-5580 (owner's manual-testing instance)" >&2; exit 1; }
app_id="${APP_ID:?set APP_ID to the debug applicationId}"
out="${E2E_OUT:?set E2E_OUT to a new output directory}"
apk="${E2E_APK:-androidApp/build/outputs/apk/debug/androidApp-debug.apk}"

[ -f "$apk" ] || { echo "missing $apk; build it with ./gradlew :androidApp:assembleDebug first" >&2; exit 1; }
mkdir -p "$out"

echo "target device: $device"
adb -s "$device" get-state

adb -s "$device" install -r "$apk"
maestro test --device "$device" \
  -e "APP_ID=${app_id}" \
  --format junit --output "$out/report.xml" \
  --test-output-dir "$out/maestro" \
  e2e/smoke.yaml
