# Host 0.5.0 — first original Bedrock Activity attempt

**Implementation status: Android build, APK packaging checks and Android 13 regression suite passed. First original-game Activity launch result remains pending.** [Validated host APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39973150833/artifacts/12509773681). This is the next controlled step after [the returned bootstrap](JVM_BOOTSTRAP_DEVICE_TEST.md), not a repeated link/demo test.

## What changed

- A separate, private **`:game_host`** process owns preparation, one loader and the original Activity. Bedrock's `System.exit(0)` shutdown cannot close the host UI. The working one-shot bootstrap remains separate.
- Preparation checks ARM64, exact **1.26.50.4 / 972605004**, reviewed Application/factory/Activity metadata, the supplied manifest, **all five DEX files and resource table SHA-256**, and the exact selected installed engine. The first adapter accepts only the reported single-APK layout. Supplied DEX bytes, including their header discrepancies, are never repaired.
- A host-side subclass extends the original installed `com.mojang.minecraftpe.MainActivity`. Only our wrapper/bridge are child-defined specially; actual MainActivity/GameActivity and native libraries share the prepared loader. A tiny **compile-only JAR** supplies superclass signatures during compilation, never runtime code. CI checks actual APK DEX class definitions to reject bundled Minecraft/GameActivity stubs, engine libraries or input archives.
- The host's private AppComponentFactory returns the configured subclass to Android. Android performs attachment, window/lifecycle and Surface delivery; original superclass/native calls remain untouched. No manual `Activity.attach`, `onCreate` invocation or native function pointer is used.
- Activity and dedicated-process Application resource views use original installed game assets/resources. UID, package identity, permissions, services and storage remain the host's; the installed game's private worlds/accounts are not read. Host component metadata supplies `android.app.lib_name=minecraftpe`, and the framework placeholder theme maps to the reviewed game theme. Later original theme changes are retained.
- A separate controller/report records stages, process identity, creation/resume and passive Surface observations, handles user stop/startup timeout, and collects same-UID/PID/process/time-filtered Android exit diagnostics. It reuses the existing bounded tombstone/logcat collector with an explicit `:game_host` allowlist, without overwriting probe run metadata. No auto-relaunch/recreation or reconnect execution loop.

## Loader ownership correction found by the AppCompat fixture

AndroidX/Kotlin implementation namespaces are guest-owned as whole packages, with no parent fallback for missing guest classes. Platform classes and the shared host contract remain parent-owned. The new fixture caught a real split-package `IllegalAccessError` when parent instrumentation supplied Lifecycling but the guest supplied LifecycleRegistry. The correction keeps both in the same guest loader and is asserted by the recreation/resource tests. This deliberate policy change also applies to the original-game loader; it is not an unrestricted child-first override of the host or Android framework.

## Deliberate capability changes

The host now also declares normal install-time **`INTERNET`**, alongside `ACCESS_NETWORK_STATE`. This is for the original networked SDK/Activity startup seen in the supplied code and manifest, not to bypass permission enforcement. Both are shared by trusted guest code under the host UID; consent names Internet access. No runtime permission dialog, storage, microphone, location, broad-log or license/billing grant is added.

The developer AppCompat Activity fixture adds AndroidX's own app-specific **signature** permission for its private-receiver fallback. This is not an Android platform capability or a permission inherited from Minecraft. Its source/merged declaration must stay app-specific.

## Boundaries and likely follow-up failures

This is an **initial Activity adapter**, not a complete Android app-virtualization implementation. Original providers are **not installed**, package-manager identities/authorities are not spoofed, host ApplicationInfo is not replaced, all SDK environment assumptions are not recreated, and legitimate account/entitlement requirements are not bypassed. A provider/package/resource/permission/SDK error must be reported at its actual stage and fixed narrowly; a constructor/onCreate/Surface receipt alone is not title-screen success.

The original wrapper is compiled but cannot execute in x86_64 CI without the original installed ARM64 game. The automated resource/lifecycle fixture now uses **AppCompat**, not a plain Activity. Policy/rejection/manifest/own-process filtering and the packaging checks complement that fixture; they are not a captured Bedrock launch.

Startup has a 180-second bound until original onResume returns; diagnostic UI waits at most 12 seconds and ignores late callbacks. User stop or timeout SIGKILL is labelled, not treated as proof of the original cause. Original Activity destruction may call System.exit normally. Exit matching retains the collector's first-15-minute run window; this build is a controlled startup experiment, not a long-session crash recorder. The bounded collector may still be unavailable or partial on an OEM device.

## Next phone step (only after validated artifact is recorded)

Install only the new host, keep Minecraft unchanged. Tap **Launch Bedrock Activity (experimental)** once and accept execution consent. If preparation finishes while the host is backgrounded, return to the host to continue the already-consented launch. No import, demo, link or bootstrap repeat is needed.

If the game window appears, report what is actually visible. Return to Client Base and **Share Bedrock Activity report**. If it exits, allow automatic exit collection; **Collect last game exit (no game execution)** retries without launching. Use **Stop hosted game process** for a hang (cleanup is labelled). Review reports before sharing. Do not confuse the old probe's share/diagnostic buttons with the separate Activity report.

No new file from the user is currently required. A corrected phone startup result, not CI alone, determines the next missing prerequisite.

## Validated build

[CI run 39973150833](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39973150833) passed for app/test commit **`d3e49030c6ddf9aaaa6d31b52c572ad2f032b49d`**, job **`120481541654`**, **4m41s**. Host version **0.5.0 / code 8**. Android 13 x86_64 runs the 30-case regression suite (retained coverage, AppCompat Activity/resource/configuration/Surface cases, reviewed manifest policy, dedicated-process component metadata/rejection and cross-process exit filtering); all passed. Twenty-two local source/native guards pass. Actual original ARM64 Bedrock Activity/SDK execution is **not** performed by this emulator suite.

- [APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39973150833/artifacts/12509773681): **`client-base-debug-66`**, **6,191,208 bytes**. Install only **`client-base-host-debug.apk`** for the phone attempt.
- [CI reports](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39973150833/artifacts/12510646294): **`client-base-device-tests-66`**, **211,976 bytes**. The CI conclusion is recorded; local downloading/parsing of artifact XMLs is not claimed.
- Actual APK class-definition scans passed: no compile-only Mojang/GameActivity class, engine binary or input archive shipped.
- The original supplied engine archive blob is unchanged: `c78f6e8642d334916d247487897eb871ad27c160`.

Build iterations fixed a Java forward reference in the startup timer, aligned AppCompat's transitive Kotlin stdlib variants using BOM 1.8.22, and corrected the AndroidX split-package runtime failure above. No tests were disabled. Bounded Gradle failure annotations were added because raw CI-log downloads are blocked in this sandbox. The passing artifact above includes the corrected implementation, not the earlier failed builds.
