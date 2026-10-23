# Host 0.4 — controlled original Java / JNI initialization

## Why this is the next experiment

The [phone bootstrap report](BOOTSTRAP_DEVICE_TEST.md) establishes that the original MainActivity loads the native dependencies and `minecraftpe` itself. This experiment therefore uses **ART's normal class initialization/native loading**, not a manual `dlsym` function-pointer call. It is not Activity hosting or a game launch.

Upstream [AOSP-mirrored GameActivity source](https://github.com/msft-mirror-aosp/platform.frameworks.opt.gamesdk/blob/0fea90e46030a34d8845330ff5bc091ec7e39ed6/game-activity/src/main/java/com/google/androidgamesdk/GameActivity.java) has no static initializer and performs native Activity initialization from `onCreate`. That is context, **not proof of the installed Minecraft subclass hierarchy**. The actual installed GameActivity DEX is inspected before this experiment proceeds.

## Preconditions and execution

- Explicit UI consent: execute only the user's trusted, original installed Minecraft. Native hashing is not Java signing-certificate authentication. This private process shares the host's UID/permissions; it is crash separation, **not a security sandbox**.
- ARM64 and exact reported **versionName 1.26.50.4 / versionCode 972605004**.
- Installed engine matches the supplied SHA-256. MainActivity's 45-unit initializer and its resolved references must match the reported build, including the six native library names and normal `System.loadLibrary` calls.
- Inspect and save the **actual GameActivity DEX**. An unexpected direct superclass or any GameActivity `<clinit>` blocks initialization and returns evidence, rather than guessing at its behavior. This does not audit every AndroidX/framework/transitive SDK initializer; consent allows those normal JVM initialization effects.
- Build one `DexClassLoader` containing our APK plus the installed game's original base/split APKs. Parent-first loading is retained except for **our bridge only**, which must be defined in the same child loader. No fake Minecraft/GameActivity classes or patched APKs are supplied.
- Resolve the engine **without loading it**, then independently hash the exact loader-selected installed file/APK entry. Reject other paths or a mismatch before defining/initializing game classes. This selects the same engine bytes the user supplied, directly from the matching installed original APK/native directory; an imported duplicate is **not required**.
- Resolve the real MainActivity without initialization and verify it and GameActivity belong to the intended child loader. Then initialize it on the private process's **main looper**, with that thread's context class loader temporarily set to the guest loader. The JVM native-library owner is established by the original `System.loadLibrary` calls, not by the context-loader setting.
- After class initialization returns, our bridge uses **`RTLD_NOLOAD`** only to inspect the already mapped engine and `JNI_OnLoad` ownership. It does not load it again or call the pointer. ART mediates the engine JNI callback; its exact return value/call count are **not separately instrumented** by this probe.

No Minecraft Activity instance, original Application/providers, lifecycle callbacks, resources adapter, native game-loop entrypoint, Surface or title screen is created. No native getters, account/entitlement flags or integrity-token functions are called. No hooks, binary patches, private-data access or entitlement bypass are included.

## Results mean only what they say

- **`JVM_BOOTSTRAP_RETURNED (not game startup)`**: original MainActivity class initialization returned and an already mapped, hash-pinned engine was observed through that loader. This is not a title screen, full SDK/lifecycle correctness, or separate instrumentation of Minecraft's JNI callback.
- **`PROBE_BLOCKED`**: failed prerequisite, Java/JNI/linker error, or observation failure; includes nested causes and the last saved stage. An unreviewed superclass initializer is an expected, non-executing stop.
- **Process disconnect / timeout**: possible native crash, hang, OS termination or interruption. The last atomic stage is evidence of how far we got, not proof of the cause.

Initialization/SDK callbacks can crash or hang. Stages are saved before entering it and before native-library lookup returns to the JVM. If the child's main thread hangs, its `onDestroy` cannot run: the UI controller can kill only the current, reported **own-UID child PID**, never its own PID or a PID remembered from a previous run. The 180-second controller timeout remains active. The child is also terminated after a result; no invented Minecraft shutdown sequence or engine unloading is attempted.

## Phone steps

1. Install **host 0.4** from the validated APK artifact. No demo/test APK is needed for this experiment. If the debug key changed, uninstall **Client Base host only**, not Minecraft.
2. Tap **Initialize Minecraft Java / JNI (no Activity)** and read/accept the execution consent. Keep the host open.
3. Use **Share current engine / bootstrap report** and send the result, including a blocked/last-stage result if it does not return normally.

Do **not** repeat the demo or link test. No archive import is needed: the loader-selected installed engine must independently match the provided library's exact SHA.

## Tests and validation boundary

The new emulator fixture runs **our own copied native bridge**, not Minecraft: a fresh child loader owns its Java class and native library; ART invokes our JNI callback exactly once; JNI calls resolve through that loader; and `RTLD_NOLOAD` can inspect the loaded copy but must not load another existing copy. Additional tests accept the reported initializer metadata and reject changed code/references, truncation and unreviewed superclass initializers.

These are meaningful JVM/loader checks, not a Minecraft execution result. Actual installed GameActivity and real JNI initialization still need the phone report. Build/workflow success must be recorded separately, not assumed from the presence of this implementation.

### Validated host 0.4 build

App/test commit **`bf94035495a5a0c0f078d8c2e559a03896441dc4`**, host **0.4.0 / code 4**, passed compilation and Android 13/API 33 x86_64 integration in [run 39905493730](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39905493730), job **120256815483**, **4m59s**. The three new JVM/policy cases pass alongside the previous ten cases, including mandatory actual engine-archive import/hash checks as data only. Fourteen local source/native checks pass; seventeen Java sources syntax-parse. Minecraft initialization is not exercised by CI; timeout child-PID guards are source-checked, not a captured real-game hang test.

- [APK ZIP: client-base-debug-35](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39905493730/artifacts/12480831820) — **3,460,302 bytes**. Install only `client-base-host-debug.apk` for this phone experiment.
- [Device reports: client-base-device-tests-35](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39905493730/artifacts/12480538812) — **137,251 bytes**.

### Later Activity-path evidence, not invoked

A bounded `.dynsym` lookup in the unchanged SHA-pinned supplied engine finds **`GameActivity_onCreate`** (ELF VA `0x7f7c6b8`, 620 bytes) and **`Java_com_google_androidgamesdk_GameActivity_initializeNativeCode`** (ELF VA `0x7f791c4`, 116 bytes). This is consistent with the reported GameActivity superclass and explains why absence of `ANativeActivity_onCreate` was not proof of a missing custom/native adapter path. These are build-specific ELF addresses, not process addresses or verified calling signatures. Neither export is called by host 0.4; the original Activity lifecycle/resources environment must be prepared before that later step.
