# Implementation status and acceptance gates

## Current direction

**Latest phone milestone:** **[The user confirms a fully functioning hosted launch](HOSTED_LAUNCH_SUCCESS.md) after host 0.5.4.** This is the first reported working Bedrock launch, not merely onCreate/Surface progress. Preserve app/test commit `287e04b` and [the validated 0.5.4 APK](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39988805056/artifacts/12516682587) as the working baseline. The confirmation is user-reported, without a new PID/UID or capture; it does not establish every SDK feature or long-session stability. No engine reimport or repeated bootstrap/demo test is needed. Native-memory integration/hooks remain the next development stage, not a completed feature.

**Current development:** [0.6.0 external controls + on-demand memory inspection](MEMORY_CONTROLS.md), [Android 13 build/APK and 54-case regressions passed](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39993673423). [Host 0.6.0 APK](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39993673423/artifacts/12518682891). Separate UI task/process, no game-renderer-attached GUI, read-only ELF header/build-ID/segment snapshot through fixed private IPC. Split-screen remains an optional real-device compatibility experiment. Native player-field manipulation/hooks remain unimplemented.

We are building a clean, independent hosting client, not porting the original protected native VM. The current repository contains only the retained engine archive and the source/documentation/build infrastructure needed for the new base.

**New development — 0.6.1:** [Optional external Java overlay](EXTERNAL_OVERLAY.md) is implemented, Android build/emulator validation pending. User-authorized extension of the screen-only UI: same APK/UI process, non-focusable bounded windows over other apps, explicit permission, non-sticky foreground service/Stop cleanup, real manual memory IPC. Separate task remains fallback. No game renderer/input hooks, fake switches or proven physical-game focus/performance result.

### Implemented

- Host UI with explicit demo loading and readable failure messages.
- Fixed guest package lookup and base/split code paths.
- Signing-certificate and protocol checks before guest code execution.
- Parent-first `DexClassLoader` with a shared contract interface.
- Guest resources and a View created by guest code within a real host Activity.
- Native PID/UID diagnostics from a separate guest JNI library.
- A process-lifetime loader cache to prevent duplicate JNI loading after Activity recreation.
- Separate standalone demo app for comparing installed versus executing UID.
- ARM64 and x86_64 native builds; min SDK 30, target SDK 33, compile SDK 35.
- GitHub Actions APK build and Android 13/API 33 integration-test configuration.

### Engine experiment — phone link passed as reported

The host now accepts the exact supplied archive/raw library, verifies its hash, checks the installed game's ARM64 engine matches before using its dependency paths, and offers a private-process `dlopen` diagnostic. It does not explicitly invoke the engine's `JNI_OnLoad` or any game entrypoint. See [ENGINE_PROBE.md](ENGINE_PROBE.md) and [the static linkage inventory](ENGINE_LINKAGE.json). Linker constructors may execute; a successful link is not Minecraft startup.

**Phone result:** the user reports `LINK_CHECK_SUCCESS`, engine hash matching installed Minecraft **1.26.50.4 / 972605004**, and `JNI_OnLoad` resolving to our imported library. See [the engine device record](ENGINE_DEVICE_TEST.md). JNI initialization and game startup remain untested.

### JNI/bootstrap step — actual MainActivity inspected; initialization experiment implemented

Bounded static inspection of the supplied engine shows `JNI_OnLoad` needs the real `com/mojang/minecraftpe/MainActivity` class loader and changes the SIGTERM handler. Its normal success return is JNI 1.4. The existing link-only loader does not contain Minecraft Java code, and manual function-pointer invocation would not itself establish JVM native-library ownership.

Host **0.3.0** adds a separate, match-gated **static DEX bootstrap inspection** and a share-report button. It reads MainActivity's superclass, native declarations and bounded `<clinit>` instructions without defining/initializing game classes or loading game native code. The user has now supplied a successful report: 45 MainActivity initializer code units, superclass `com.google.androidgamesdk.GameActivity`, 35 native declarations and native loads ending in `minecraftpe`. See [the phone record](BOOTSTRAP_DEVICE_TEST.md). This executed no game initialization. See [JNI_BOOTSTRAP.md](JNI_BOOTSTRAP.md) for findings, limits and the next phone step. This does not invoke `JNI_OnLoad` and does not complete Gate 2.

**Host 0.3 validation:** [run 39902922705](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39902922705) passed compilation and Android 13 emulator integration for app/test commit `d132ae71a5dd233e0cda1f99e0f398b0f950c8f9` (5m17s). It includes the no-initialization sentinel, real demo DEX decoding, malformed-header/table checks and instruction-width cases. [Download APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39902922705/artifacts/12479691483), `client-base-debug-32`. For this phone step install only the host, inspect bootstrap and share the report. No new demo/link test or engine import is needed.

### Host 0.4 — controlled JVM initialization, phone execution pending

[The new experiment](JVM_INITIALIZATION.md) verifies the reported MainActivity initializer and the actual direct superclass DEX, blocks an unreviewed GameActivity `<clinit>`, and hashes the exact loader-selected installed engine. With explicit consent it can initialize the original MainActivity class on the private process main looper through one guest class loader. Original `System.loadLibrary` calls let ART mediate JNI initialization; no manual function-pointer invocation, fake classes, binary patch or native getter is used.

No engine re-import is needed: the selected installed engine must have the supplied library's exact SHA. No Activity instance/lifecycle or title screen is created. Parent/SDK initialization can still fail or hang; results/last stages and nested causes are saved, and the controller can terminate the reported own-UID child even if its main looper is stuck. Actual GameActivity/JVM initialization results remain pending. **Validation:** [run 39905493730](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39905493730) passed compilation and Android 13 emulator checks for app/test commit `bf94035495a5a0c0f078d8c2e559a03896441dc4` (4m59s), including the new own-bridge JVM/native-library and initializer-policy cases. [Host 0.4 APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39905493730/artifacts/12480831820), `client-base-debug-35`. This is not a real Minecraft initialization result.

### Host 0.4.1 — reviewed empty superclass initializer

The user reported host 0.4 stopping at the superclass gate, **before game initialization**, with GameActivity's actual initializer: zero registers and one `000e` (`return-void`) code unit, no references, complete extraction. See [the normalized phone result](GAMEACTIVITY_DEVICE_TEST.md). This is a no-op, not a Minecraft JNI failure; the absent-only policy was overly strict.

Host 0.4.1 / code 5 narrowly accepts that exact empty form as well as absence. Longer/changed/partial code, references, extra registers, missing data and unexpected classes remain blocked. Other gates and the Java/JNI initialization path are unchanged. Fifteen local checks pass; Android regression coverage is extended for the no-op and rejected mutations. **Corrected build validated:** [run 39911337051](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39911337051) passed compilation and Android 13 emulator regression checks for app/test commit `8399e9750c0fd46012e49ce2a743948f1c90ac76` (5m0s). [Current host 0.4.1 APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39911337051/artifacts/12483002590), `client-base-debug-38`. Actual phone JNI execution remains pending; no title screen is claimed.

### Host 0.4.2 — JVM initialization entered; private process disconnected

The user reports PID 18392 / UID 11068 reaching `JVM_INITIALIZATION_IN_PROGRESS` after the corrected no-op gate. Last lookup is `PlayFabMultiplayer`; it is not a completion receipt or proof of the culprit. No completed bootstrap, exit reason/signal, abort message or native frames were supplied. [Recorded disconnect and diagnostic plan](EXIT_DIAGNOSTICS.md).

The initialization sequence is unchanged. Host 0.4.2 adds own-UID/PID/process/time-filtered Android exit history, available native tombstone selected-field summaries, bounded own-PID logcat, automatic post-disconnect collection and a no-execution retry button. Cleanup SIGKILL is labelled; unexpected disconnect no longer directly triggers a UI SIGKILL. Changed debug keys/uninstalls can lose old-UID history, so a new controlled attempt may be required. **Validation:** [run 39931616080](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39931616080) passed compilation and Android 13 integration for app/test commit `2ac0b627eb1369c2824661fe783ebea3744455f2` (4m58s), including the parser/filter and real own-child-termination cases. Seventeen local checks pass; twenty Java sources syntax-parse. [Host 0.4.2 APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39931616080/artifacts/12491479101), `client-base-debug-41`. At that point the phone failure cause was unresolved; the later 0.4.2 diagnostic below identifies it. Real Minecraft JNI/bootstrap completion remains unproven.

### Host 0.4.3 — missing permission identified and corrected

The user supplied a real selected native tombstone: PID 31985 / UID 11070, Android reason `NATIVE_CRASH`, SIGABRT, and a pending `SecurityException` for missing **`ACCESS_NETWORK_STATE`** from Xbox `NetworkObserver.Initialize`/`registerNetworkCallback`. `FindClass` in PlayFab JNI initialization then triggered ART's abort. [Selected evidence and correction](NETWORK_PERMISSION_DEVICE_TEST.md). This is a concrete host prerequisite failure, not just a suspicious last library lookup.

Host 0.4.3 adds only that normal install-time permission, verifies it before game class loading/initialization, and keeps original load order, all identity/code gates, CheckJNI and diagnostics unchanged. No INTERNET or other permission is added speculatively; demo/contract still request none. Tests cover actual permission grant, denied preflight and network callback registration/unregistration with our context, without running Minecraft or needing network traffic. Eighteen local guards pass. [Corrected Android 13 build and regression suite](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39959687156) passed for app/test commit `9a1d0a20b6c5f1c0ed84ff73b0dea9820b8fcda5`, job `120436542251` (4m19s), including the three permission tests. [Host 0.4.3 APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39959687156/artifacts/12505205745), `client-base-debug-44`. The subsequent [phone report](JVM_BOOTSTRAP_DEVICE_TEST.md) now establishes returned class initialization and an already mapped engine. The engine JNI callback is not separately instrumented; no Activity/title screen is claimed.

### Framework Activity bridge — implementation now validated

The [first Activity/resource bridge](ACTIVITY_BRIDGE.md) is implemented, not just planned: a fixed private host component is created through AppComponentFactory as our trusted guest Activity; Android attaches the real window and delivers lifecycle/Surface callbacks. Guest resources/theme/inflater and one cached native owner survive recreation while package identity, Application and storage stay the host's. Four new automated tests cover creation, recreation, real Surface delivery and factory rejection/default behavior. These are developer tests, not a request to redo the phone demo.

[Android 13 run 39966947699](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39966947699) passed for commit `3cd8f7f1e705de3892952d58230b3b7c862cfb7c`, job `120459176807` (5m2s), with all retained coverage; 20 local source/native guards pass. Real Minecraft is not executed by CI. The actual original Activity/subclass attachment, AppCompat/SplashScreen and Application/provider/SDK environment, separate-game-process lifetime/diagnostics, and user-facing launch are still pending. Bounded inspection found the supplied MainActivity exits its process from `onDestroy`; original-game hosting must therefore never use the host UI process. No additional file or phone test is currently requested.

### Host 0.5.0 — original Activity adapter and Android validation complete; phone launch pending

The [first original Bedrock Activity adapter](BEDROCK_ACTIVITY_ATTEMPT.md) adds a dedicated `:game_host` session/service/factory, an exact same-loader host subclass of the installed MainActivity, original resource/theme views and native-library metadata, passive lifecycle/Surface stages, separate reports, own-process exit collection and explicit stop/startup timeout. All uploaded manifest/DEX/resource bytes are pinned before preparing classes, as is the selected engine. A compile-only superclass-signature JAR is excluded from APKs; the packaging guard checks actual DEX class definitions. No proprietary runtime class is bundled or patched.

Original providers and all SDK/package environments are not recreated. INTERNET is deliberately added for original networked startup; no runtime/broad storage/location/log grant or entitlement bypass. The automated fixture now uses AppCompat, with the app-specific signature permission its private receiver fallback declares. [Android 13 CI run 39973150833](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39973150833) passed for app/test commit `d3e49030c6ddf9aaaa6d31b52c572ad2f032b49d`, job `120481541654` (4m41s): all 30 regression cases plus APK class-definition/engine exclusion checks. Twenty-two local guards pass. The fixture validates AppCompat with consistent guest AndroidX/Kotlin ownership; original ARM64 game execution is not performed in CI. [Host 0.5.0 APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39973150833/artifacts/12509773681), `client-base-debug-66`. The actual Bedrock title screen remains unproven. No extra user file is needed at this point.

### Validation boundary

Eight local checks passed before and after the repository migration; the engine changes expanded the local suite to ten checks; the bootstrap diagnostic added two source guards and JVM initialization adds two more guards (fifteen total, including the reported no-op guard). These are source guards plus an actually compiled/executed **Linux** native PID/UID test. The six Java sources also passed syntax parsing before migration; the Android source was moved unchanged.

The local sandbox does not have an Android SDK and cannot fetch the Gradle distribution. Therefore local checks are **not** evidence of a successful Android build or device run. Check the [workflow runs](https://github.com/Alextheplayer919/Zips-ig/actions) for actual compilation, artifact and emulator outcomes. A workflow definition alone is not a passing test.

**CI result:** APK compilation and Android 13/API 33 x86_64 integration passed in [run 39840759440](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39840759440). The user subsequently reported a successful first phone load; see [the device test record](DEVICE_TEST.md). Repeated phone loads and rotation also passed as reported. The phone rotation was not instrumented to verify Activity recreation; the emulator test covers explicit recreation.

The original two Android instrumentation tests cover:

1. Guest class-loader identity, JNI PID/UID matching the host, different installed guest UID, and guest resources.
2. Actual load-button behavior and reloading after host Activity recreation.

The engine build also passed [CI run 39851721708](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39851721708), adding wrong-input rejection, native-bridge link success/failure, and mandatory actual archive extraction/hash verification as data on x86_64. That CI does not execute the ARM64 engine.

### Not implemented

- Proven original Bedrock title-screen launch and a general guest Application/provider environment. The original Activity adapter is now experimental in 0.5.0; its first phone launch result is pending.
- Minecraft native-engine startup, resolver reconstruction or hooks.
- Access to the separately installed game's private data.
- Game features/modules or an update-compatible engine integration layer.

The original client's successful Android 13 / Minecraft 26.50 launch is **user-reported**. It is not a test result for our base. The supplied engine now has a user-reported hash match to installed versionName **1.26.50.4**, versionCode **972605004**; this is not independent device capture.

## Gate 1 — cooperative demo on Android 13

**Current result:** initial phone load passed as reported: native PID/UID match the host, and the installed guest UID differs. Repeated loads and rotation also passed as reported. **Gate 1 is complete at this user-reported functional-check level.** No phone lifecycle trace was supplied; explicit Activity recreation is covered by the emulator test.

**Pass:** both same-build APKs install; the host loads the guest resource/View; native PID/UID equal host PID/UID; installed guest UID differs; repeated loads and Activity recreation work. Confirm on the user's ARM64 phone, not just an x86_64 emulator.

**Fail:** fix the specific class-loading, signing, resource, ABI or native-path problem. Do not add Minecraft hooks or restart protected-VM trace expansion to compensate.

## Gate 2 — hosting-only Minecraft adapter

**Hosting gate: successful functioning launch reported by the user after host 0.5.4.** [Milestone, evidence limits and working baseline](HOSTED_LAUNCH_SUCCESS.md). Earlier engine/JVM/onCreate/resume/Surface results are retained as historical progress. No new independent process capture, all-SDK certification or long-session stability result is claimed. No repeat launch is required solely to acknowledge this confirmation.

After Gate 1, build a separate adapter for the original installed base/split APKs. Do not loosen the demo trust policy into an unrestricted plugin loader; Minecraft requires its own explicit package/trust and lifecycle design.

**Pass:** the original game reaches its title screen in the intended host process on Android 13 / target Minecraft 26.50, with package/lifecycle/native-load behavior recorded. No gameplay features or binary patches are required for this gate.

**Blockers must be explicit:** identity/signature assumptions, missing dependencies/splits, native startup, lifecycle failures or legitimate entitlement requirements. A loading screen or a mapped library alone is not a pass.

## Gate 3 — controlled native integration

Only after hosted Minecraft runs, establish engine identity/mapping inside our own process and determine the minimum interfaces needed. Begin with read-only diagnostics. Choose a hook technique only when the actual integration point is understood.

No compatibility, engine address, native binding or hook is assumed from old traces, a string marker or a large test count.
