# JNI bootstrap: bounded inspection and next device diagnostic

## Evidence and scope

The supplied ARM64 engine linked on the phone **as reported**, matching installed Minecraft **1.26.50.4 / 972605004**. That remains a link result, not game startup. This inspection advances from that result; it does not repeat the cooperative demo or expand the old VM traces.

[JNI_BOOTSTRAP.json](JNI_BOOTSTRAP.json) records a static inspection of the exact SHA-pinned library. The exported `JNI_OnLoad` is **288 bytes at ELF VA `0x852e0e0`**. Branch thunks were followed to real local functions or PLT/GOT imports. Addresses below are build-specific **ELF virtual addresses**, never hardcoded process addresses.

## What the JNI entry actually does

1. Logs entry using `pthread_self` and `__android_log_print`.
2. Writes a local state marker and stores the supplied `JavaVM*`.
3. Calls **`sigaction(15/SIGTERM, …)`**, using `SA_SIGINFO` and a handler at `0x852e200` that immediately returns. This is a **process-wide change**, another reason not to initialize it in the launcher UI process.
4. Calls a local class-loader helper with the exact anchor **`com/mojang/minecraftpe/MainActivity`**.
5. On the ordinary success branch, returns **`JNI_VERSION_1_4` (`0x00010004`)**. Class-loader failure returns `0`; the signal-install failure path returns `1`. Neither failure value is a valid successful JNI version.

The inspected class-loader helper obtains a JNI environment, pushes a local frame, finds the anchor class and `java/lang/ClassLoader`, calls the anchor class object's **`getClassLoader()Ljava/lang/ClassLoader;`**, and retains a global reference to the resulting loader plus the **`loadClass(Ljava/lang/String;)Ljava/lang/Class;`** method ID. Its cleanup includes exception diagnostics. Missing prerequisites must not be treated as a safe, successful initialization.

No Activity instance, rendering surface or game loop is explicitly created in this inspected entry/helper path. This does **not** establish that Java initialization or later callbacks have no further requirements.

## Why not just call the function pointer?

The current link probe's child loader contains only **our own bridge bytecode**. Installed game paths were added for **native dependencies**, not game Java classes. The actual MainActivity is therefore not available through that loader.

Adding game DEX paths is necessary but not sufficient. We must inspect the real Java static initializer (`<clinit>`) before choosing the load sequence. Java/JNI class lookup and initialization can involve further library loads. If MainActivity itself loads `minecraftpe`, a second manual initialization would be the wrong sequence. Moreover, manually calling a `dlsym` result does **not by itself register the engine as a JVM native library owned by the intended class loader**; later Java native-method resolution is a separate requirement. Changing the thread context class loader alone is not a substitute for that association.

The next initialization experiment should use an understood JVM-managed native-load path in a fresh private process, with one consistent loader and library-path identity. It is **not implemented or claimed safe yet**. No fake MainActivity class, binary patch, fixed mapping base or guessed native signature is introduced.

## Host 0.3: inspect the installed Java bootstrap without running it

New button: **Inspect Minecraft bootstrap (no code execution)**.

- Uses public base/split APK paths from the fixed installed Minecraft package.
- Rechecks that the installed ARM64 engine has the supplied SHA-256. An imported engine copy is **not required** for this read-only diagnostic.
- Reads DEX files **as data** in the existing private `:engine_probe` process. It does not define, reflect on or initialize Minecraft classes and does not load any game native library.
- Reports MainActivity's superclass, native declarations, and at most **256 code units** of its `<clinit>`, with resolved method/field/type references. Known native-library literals are included; other string literals are omitted from readable references.
- Reports truncation, unsupported instructions or incomplete reference scanning explicitly; invoke-custom/method-handle/type pools are not resolved. A complete local instruction scan is **not** analysis of reachable Java initializers or lifecycle correctness.
- Limits input to 128 MiB per DEX, 256 MiB total copied DEX and 32 DEX entries; temporary files are deleted on normal completion/error, with stale scratch cleanup at the next inspection after a forced process kill. Unsupported DEX versions/layouts, selected non-ASCII/oversized names and invalid offsets fail closed rather than invoking a runtime fallback.
- Supports ordinary DEX 035/037/038/039/040, not compact DEX or DEX 041 containers. Diagnostic name/signature/reference limits may block unusually large or differently encoded inputs.

**Phone steps after the new APK is built:** install host 0.3, keep the matching Minecraft installation, tap the new inspection button, then **Share current engine / bootstrap report** and send the text back. **Do not rerun the demo or engine link probe for this step.** Inspecting does not need an engine re-import. A debug signing-key mismatch can require uninstalling only the old Client Base host first; that removes its imported copy/reports, not the Minecraft installation.

A `BOOTSTRAP_INSPECTION_COMPLETE` report means only that this static extraction succeeded. It is not JNI initialization, correct native binding, Minecraft launch, or the Gate 2 title screen.

## Reproduce the native analysis

Use Python 3.11+ and an extracted copy of the unchanged retained library outside tracked files:

```sh
python -m pip install -r tools/requirements-engine.txt
python tools/inspect_jni_bootstrap.py .cache/engine-inspection/libminecraftpe.so \
  --output docs/JNI_BOOTSTRAP.json
```

The tool verifies the complete library SHA before inspecting a bounded set of instruction windows. The JSON separates raw call/literal/window evidence from manually audited interpretation. It does not run ARM64 instructions.

## Validation

Local source/native guards and Java syntax checks cover repository invariants, not Android execution. New Android instrumentation cases read our real demo DEX, resolve its `System.loadLibrary("clientbase_demo")` call and two native methods, verify a sentinel static initializer is not executed, and reject malformed headers/tables and unsupported instruction encodings. Actual build/emulator results must come from the workflow, not this description. No matching Minecraft Java APK is available in the development sandbox; its initializer remains an explicit device-data prerequisite.
