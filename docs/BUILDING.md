# Building and testing APKs

**Current integration build:** [host 0.6.2 APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/40000628335/artifacts/12522700433), [Android 13 build/APK and full 60-case suite passed](https://github.com/Alextheplayer919/Zips-ig/actions/runs/40000628335). Install only **client-base-host-debug.apk**. [Category click GUI and inputs for future modules](CLICK_GUI.md). Floating menu has Movement, Combat, Visual, Misc; memory refresh/copy/report options stay in the separate host Controls screen. Existing explicit overlay permission/Enable/launch flow unchanged; [permission instructions](EXTERNAL_OVERLAY.md#validated-overlay-build-and-phone-steps). No auto-start, new permissions, fake modules, game-renderer hooks or native startup/backend change. Keep working 0.6.1 and 0.5.4 APKs as references. No demo/import/link/bootstrap repeat. Earlier phone instructions below are historical.

**Latest 0.6.4 internal module build:** [host 0.6.4 APK ZIP (`client-base-debug-107`)](https://github.com/Alextheplayer919/Zips-ig/actions/runs/40015588459/artifacts/12528686610), [Android 13 build/APK and 64-case suite passed](https://github.com/Alextheplayer919/Zips-ig/actions/runs/40015588459). [Live MotionFly & KillAura engine details](MODULE_RUNTIME.md). Builds `0.6.4` (`versionCode 17`) with fingerprint-gated ARM64 `1.26.50.4` RELRO vtable bindings in `:game_host`, interactive `OFF` / `ARMED` / `ON` overlay toggles (`Movement` -> `MotionFly`, `Combat` -> `KillAura` + `Mobs`/`Multi`/`Hurt` chips), and separate `Controls` module diagnostics/disarm. Install only **client-base-host-debug.apk**. The validated 0.6.2 category-only build above remains a rollback reference.

## GitHub Actions

[Build APK](https://github.com/Alextheplayer919/Zips-ig/actions/workflows/build-apk.yml) runs for app/build changes on pushes and pull requests. It can also be dispatched manually once GitHub registers the workflow on the default branch. Push/PR runs include Android 13 emulator tests; manual dispatch offers an emulator-test toggle.

The workflow pins the Ubuntu 24.04 runner and uses JDK 21 to run Gradle 8.11.1 (app Java source/bytecode level 17), Android SDK 35/build tools 35.0.0, NDK 27.2.12479018 and CMake 3.22.1. It builds:

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

Do not uninstall Minecraft or ProtoHax for this experiment. No wireless ADB, rooting, game files, credentials or private worlds are needed. Host 0.5.x requests normal `ACCESS_NETWORK_STATE` and `INTERNET` (no runtime prompt); the developer AppCompat demo also has its own signature-only private-receiver permission. Contract requests none. Older builds requested no permissions, which caused the confirmed Xbox network-observer initialization abort.

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

Eighteen checks cover manifests/permissions, demo visibility/native extraction, protocol version, trust-check ordering, app separation, official wrapper identity, JNI names native process identity, engine fingerprint pins, private/match-gated probe configuration, static-bootstrap no-loading guards and JNI-evidence identity. The native test needs `g++`; otherwise it is skipped. These do **not** compile Android resources/classes or validate Android runtime behavior.

The wrapper files are from Gradle's official `v8.11.1` source tag. Its JAR contains the upstream license; the local suite checks SHA-256 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`.

## Engine diagnostic

Host 0.2.0 adds the opt-in [engine link probe](ENGINE_PROBE.md). The workflow additionally provisions the exact archive as a private test fixture using `run-as` on our debuggable host. Android tests extract/hash it on x86_64 **as data only**, and test our own native bridge without executing the ARM64 engine. The engine remains excluded from the APK artifact. The real link attempt needs the ARM64 phone and a matching trusted Minecraft installation.

## Current phone step: static Minecraft bootstrap report

Host **0.3.0** adds **Inspect Minecraft bootstrap (no code execution)**. For this step install just the new host APK; no demo or instrumentation APK is needed. Keep Minecraft installed and unchanged. If Android rejects the new debug signature, uninstall only Client Base host before installing it again. This inspection does not require an imported engine copy.

Tap the inspection button, then **Share current engine / bootstrap report** and send its text. Do not repeat the demo or native link test. The matching installed engine is hashed, then MainActivity DEX is read as data; no Minecraft classes or native libraries are loaded. See [JNI_BOOTSTRAP.md](JNI_BOOTSTRAP.md) for evidence, limits and the boundary before real JNI initialization.

## Next step after the successful host 0.3 report

Host **0.4** adds **Initialize Minecraft Java / JNI (no Activity)** with explicit execution consent. Install only the new host APK; keep Minecraft unchanged. No demo, repeated link/static test or archive import is needed. Send the result using the share button, even if blocked or disconnected. See [JVM_INITIALIZATION.md](JVM_INITIALIZATION.md) for the gates, native-code risks and result boundaries. Older host 0.3 inspection instructions above are historical for the completed report.

### Empty GameActivity initializer correction

Use **host 0.4.1** for the next initialization attempt. Host 0.4 stopped on a present-but-empty superclass initializer (`000e`, DEX `return-void`). The patch accepts only that exact reviewed no-op shape, preserving all other checks. Install the new host only; tap the same **Initialize Minecraft Java / JNI (no Activity)** button and share the result. No archive import, static-only inspection, demo or link test is needed. See [the recorded reason for the correction](GAMEACTIVITY_DEVICE_TEST.md).

### Current step: diagnose the initialization disconnect

Use **host 0.4.2** and see [EXIT_DIAGNOSTICS.md](EXIT_DIAGNOSTICS.md). If the update preserves host data/UID, try **Collect last probe exit details** first. A new debug key may require uninstalling only Client Base host and lose old crash-history access; then make one new initialization attempt, leave the host open for automatic exit collection, and share the full report. Retry the diagnostic button if details are delayed; it never starts game code. No demo/link test or engine import is required. Do not uninstall Minecraft.

### Host 0.4.3 correction — completed phone test

Use **host 0.4.3**. The new phone diagnostic identified a `SecurityException` for missing `ACCESS_NETWORK_STATE`, followed by a checked-JNI abort. This patch adds only that normal permission and checks it before initialization. Install the new host only, tap **Initialize Minecraft Java / JNI (no Activity)** once and share the result. There is no runtime permission prompt, engine import or demo/link/static test to repeat. Keep Minecraft unchanged. If another exit occurs, the existing automatic/manual exit diagnostics remain available. See [the recorded cause and correction](NETWORK_PERMISSION_DEVICE_TEST.md).
