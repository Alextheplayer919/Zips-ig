# Building and testing APKs

## GitHub Actions

[Build APK](https://github.com/Alextheplayer919/Zips-ig/actions/workflows/build-apk.yml) runs for app/build changes on pushes and pull requests. It can also be dispatched manually once GitHub registers the workflow on the default branch. Push/PR runs include Android 13 emulator tests; manual dispatch offers an emulator-test toggle.

The workflow uses JDK 17, Gradle 8.11.1, Android SDK 35/build tools 35.0.0, NDK 27.2.12479018 and CMake 3.22.1. It builds:

- `client-base-host-debug.apk`
- `client-base-demo-debug.apk`
- `client-base-tests.apk` (instrumentation APK; optional for manual installation)

Find these in the run's **Artifacts** section as `client-base-debug-<run number>`. Artifacts require GitHub sign-in to download and are not permanent releases. They are uploaded immediately after successful compilation, before emulator testing: check the job steps to distinguish a successful build from a passing device test.

No secrets/signing credentials are required. These are **debug** APKs, not production-signed releases. The engine archive is not an app dependency or Android asset and is never copied into the APK artifact.

## Phone-only installation

1. Open the repository's **Actions** tab in your phone browser and select the relevant **Build APK** run.
2. Download and extract its `client-base-debug-<run number>` ZIP.
3. Install both the **host** and **demo** APKs from that same ZIP. Android may request permission for your browser/file manager to install apps. You do not need the tests APK for this manual check.
4. Open **Client Base**, then tap **Load trusted demo**.
5. Expected: a `PASS` status, guest-resource text, equal host/native PID and UID, and a different guest installation UID.
6. Rotate and load again; tap repeatedly. There should be no duplicate-native-library error.
7. Open **Client Base Demo** separately to compare execution under the guest app's own UID.

Do not uninstall Minecraft or ProtoHax for this experiment. No wireless ADB, rooting, game files, credentials or private worlds are needed. Both new apps request no Android permissions.

Both APKs must have matching signing certificates. GitHub runners can generate different debug keys across runs, so updating an older pair may require uninstalling **our host/demo only**, deleting their own data. Do not mix APKs from different runs. Force-stop/reopen the host after updating the demo.

Guest code executes with the host's permissions and is not sandboxed from it. Only install the matching demo built from this project.

## Local SDK build

Install a full JDK 17 and the pinned Android tools above. Set `ANDROID_HOME` or an ignored root `local.properties` containing your SDK path.

```sh
./gradlew :host:assembleDebug :demo-guest:assembleDebug :host:assembleDebugAndroidTest
adb install -r demo-guest/build/outputs/apk/debug/demo-guest-debug.apk
adb install -r host/build/outputs/apk/debug/host-debug.apk
./gradlew :host:connectedDebugAndroidTest
```

The last command requires an emulator/device. CI supplies an Android 13 x86_64 emulator; the phone target is ARM64. The Windows entry point is `gradlew.bat`.

## Checks without an Android SDK

```sh
python3 tools/test_client_base.py
```

Eight checks cover manifests/permissions, demo visibility/native extraction, protocol version, trust-check ordering, app separation, official wrapper identity, JNI names and native process identity. The native test needs `g++`; otherwise it is skipped. These do **not** compile Android resources/classes or validate Android runtime behavior.

The wrapper files are from Gradle's official `v8.11.1` source tag. Its JAR contains the upstream license; the local suite checks SHA-256 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`.
