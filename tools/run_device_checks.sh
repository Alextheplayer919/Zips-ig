#!/usr/bin/env bash
# Emulator-side checks for our debuggable app only. Never execute the ARM64 engine.
set -Eeuo pipefail
failure() {
    echo "::error::Android device checks failed at script line $1"
    if [[ -f device-tests.log ]]; then
        grep -iE 'what went wrong|failed|caused by|exception|could not|no devices|tests? completed' device-tests.log | head -n 5 | sed 's/^/::error::Gradle device test: /' || true
    fi
    python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
reported = 0
for path in Path('host/build/outputs/androidTest-results').rglob('*.xml'):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for failure in list(root.iter('failure')) + list(root.iter('error')):
        text = ' '.join((failure.get('message', '') + ' ' + (failure.text or '')).split())
        if reported < 3:
            print('::error::' + text[:2000].replace('%', '%25'))
            reported += 1
PY
}
trap 'failure "$LINENO"' ERR
adb install -r demo-guest/build/outputs/apk/debug/demo-guest-debug.apk
adb install -r host/build/outputs/apk/debug/host-debug.apk
adb shell -T "run-as dev.zips.clientbase.host sh -c 'mkdir -p files; cat > files/engine-test.7z'" < libminecraftpe.7z
remote=$(adb shell run-as dev.zips.clientbase.host sha256sum files/engine-test.7z | tr -d '\r' | cut -d ' ' -f 1)
[[ "$remote" == a8c0828ff8d0ccd7986006242dcf1e15e665ed62e8b2a9201dbd10972396e707 ]]
./gradlew :host:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.engineFixtureRequired=true --stacktrace 2>&1 | tee device-tests.log
