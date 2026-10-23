# Implementation status and acceptance gates

## Current direction

We are building a clean, independent hosting client, not porting the original protected native VM. The current repository contains only the retained engine archive and the source/documentation/build infrastructure needed for the new base.

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

The initialization sequence is unchanged. Host 0.4.2 adds own-UID/PID/process/time-filtered Android exit history, available native tombstone selected-field summaries, bounded own-PID logcat, automatic post-disconnect collection and a no-execution retry button. Cleanup SIGKILL is labelled; unexpected disconnect no longer directly triggers a UI SIGKILL. Changed debug keys/uninstalls can lose old-UID history, so a new controlled attempt may be required. **Validation:** [run 39931616080](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39931616080) passed compilation and Android 13 integration for app/test commit `2ac0b627eb1369c2824661fe783ebea3744455f2` (4m58s), including the parser/filter and real own-child-termination cases. Seventeen local checks pass; twenty Java sources syntax-parse. [Host 0.4.2 APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39931616080/artifacts/12491479101), `client-base-debug-41`. Actual phone failure attribution and real Minecraft JNI/bootstrap completion remain unresolved.

### Host 0.4.3 — missing permission identified and corrected

The user supplied a real selected native tombstone: PID 31985 / UID 11070, Android reason `NATIVE_CRASH`, SIGABRT, and a pending `SecurityException` for missing **`ACCESS_NETWORK_STATE`** from Xbox `NetworkObserver.Initialize`/`registerNetworkCallback`. `FindClass` in PlayFab JNI initialization then triggered ART's abort. [Selected evidence and correction](NETWORK_PERMISSION_DEVICE_TEST.md). This is a concrete host prerequisite failure, not just a suspicious last library lookup.

Host 0.4.3 adds only that normal install-time permission, verifies it before game class loading/initialization, and keeps original load order, all identity/code gates, CheckJNI and diagnostics unchanged. No INTERNET or other permission is added speculatively; demo/contract still request none. Tests cover actual permission grant, denied preflight and network callback registration/unregistration with our context, without running Minecraft or needing network traffic. Eighteen local guards pass; corrected Android validation and phone bootstrap results must be recorded separately. No Minecraft title screen or completed engine JNI initialization is claimed.

### Validation boundary

Eight local checks passed before and after the repository migration; the engine changes expanded the local suite to ten checks; the bootstrap diagnostic added two source guards and JVM initialization adds two more guards (fifteen total, including the reported no-op guard). These are source guards plus an actually compiled/executed **Linux** native PID/UID test. The six Java sources also passed syntax parsing before migration; the Android source was moved unchanged.

The local sandbox does not have an Android SDK and cannot fetch the Gradle distribution. Therefore local checks are **not** evidence of a successful Android build or device run. Check the [workflow runs](https://github.com/Alextheplayer919/Zips-ig/actions) for actual compilation, artifact and emulator outcomes. A workflow definition alone is not a passing test.

**CI result:** APK compilation and Android 13/API 33 x86_64 integration passed in [run 39840759440](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39840759440). The user subsequently reported a successful first phone load; see [the device test record](DEVICE_TEST.md). Repeated phone loads and rotation also passed as reported. The phone rotation was not instrumented to verify Activity recreation; the emulator test covers explicit recreation.

The original two Android instrumentation tests cover:

1. Guest class-loader identity, JNI PID/UID matching the host, different installed guest UID, and guest resources.
2. Actual load-button behavior and reloading after host Activity recreation.

The engine build also passed [CI run 39851721708](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39851721708), adding wrong-input rejection, native-bridge link success/failure, and mandatory actual archive extraction/hash verification as data on x86_64. That CI does not execute the ARM64 engine.

### Not implemented

- Minecraft launch or a general guest Activity/Application/provider environment. Installed package discovery exists for the engine probe and static bootstrap inspection only.
- Minecraft native-engine startup, resolver reconstruction or hooks.
- Access to the separately installed game's private data.
- Game features/modules or an update-compatible engine integration layer.

The original client's successful Android 13 / Minecraft 26.50 launch is **user-reported**. It is not a test result for our base. The supplied engine now has a user-reported hash match to installed versionName **1.26.50.4**, versionCode **972605004**; this is not independent device capture.

## Gate 1 — cooperative demo on Android 13

**Current result:** initial phone load passed as reported: native PID/UID match the host, and the installed guest UID differs. Repeated loads and rotation also passed as reported. **Gate 1 is complete at this user-reported functional-check level.** No phone lifecycle trace was supplied; explicit Activity recreation is covered by the emulator test.

**Pass:** both same-build APKs install; the host loads the guest resource/View; native PID/UID equal host PID/UID; installed guest UID differs; repeated loads and Activity recreation work. Confirm on the user's ARM64 phone, not just an x86_64 emulator.

**Fail:** fix the specific class-loading, signing, resource, ABI or native-path problem. Do not add Minecraft hooks or restart protected-VM trace expansion to compensate.

## Gate 2 — hosting-only Minecraft adapter

**Engine-link substep:** complete as reported; `JNI_OnLoad` invocation, lifecycle and title screen remain pending.

After Gate 1, build a separate adapter for the original installed base/split APKs. Do not loosen the demo trust policy into an unrestricted plugin loader; Minecraft requires its own explicit package/trust and lifecycle design.

**Pass:** the original game reaches its title screen in the intended host process on Android 13 / target Minecraft 26.50, with package/lifecycle/native-load behavior recorded. No gameplay features or binary patches are required for this gate.

**Blockers must be explicit:** identity/signature assumptions, missing dependencies/splits, native startup, lifecycle failures or legitimate entitlement requirements. A loading screen or a mapped library alone is not a pass.

## Gate 3 — controlled native integration

Only after hosted Minecraft runs, establish engine identity/mapping inside our own process and determine the minimum interfaces needed. Begin with read-only diagnostics. Choose a hook technique only when the actual integration point is understood.

No compatibility, engine address, native binding or hook is assumed from old traces, a string marker or a large test count.
