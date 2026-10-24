#!/usr/bin/env bash
# Keep SDK/boot failures visible as annotations, not opaque action failures.
set -Eeuo pipefail
export ANDROID_SERIAL=emulator-5554
: "${ANDROID_USER_HOME:?Workflow must pin state before the first APK build}"
: "${ANDROID_EMULATOR_HOME:?Workflow must pin state before the first APK build}"
: "${ANDROID_AVD_HOME:?Workflow must pin state before the first APK build}"
mkdir -p "$ANDROID_AVD_HOME"
sdk="$ANDROID_HOME/cmdline-tools/latest/bin"
cleanup() { adb emu kill >/dev/null 2>&1 || true; }
failure() {
    echo "::error::Emulator verification failed at script line $1"
    if [[ -f device-tests.log ]]; then return; fi
    for log in emulator-install.log emulator-create.log emulator.log; do
        if [[ -f "$log" ]]; then tail -n 12 "$log" | sed "s/^/::error::$log: /"; fi
    done
}
trap cleanup EXIT
trap 'failure "$LINENO"' ERR
"$sdk/sdkmanager" 'platforms;android-33' 'system-images;android-33;google_apis;x86_64' 'emulator' > emulator-install.log 2>&1
printf 'no\n' | "$sdk/avdmanager" create avd --force --name client-base-api33 --device pixel_6 --package 'system-images;android-33;google_apis;x86_64' --path "$ANDROID_AVD_HOME/client-base-api33.avd" > emulator-create.log 2>&1
[[ -f "$ANDROID_AVD_HOME/client-base-api33.ini" ]]
"$ANDROID_HOME/emulator/emulator" -avd client-base-api33 -port 5554 -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -memory 2048 -cores 2 -accel on > emulator.log 2>&1 &
timeout 120 adb wait-for-device
booted=false
for attempt in $(seq 1 150); do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
        booted=true
        break
    fi
    sleep 2
done
[[ "$booted" == true ]]
adb shell input keyevent 82
bash tools/run_device_checks.sh
