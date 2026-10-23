# Installed Java bootstrap — phone result

**Evidence:** the user pasted the host 0.3 report, not an independently captured device trace. [Normalized complete metadata](BOOTSTRAP_DEVICE_TEST.json) preserves the native declarations, initializer code units/references and DEX provenance. Escaped slashes and HTML-escaped arrows were decoded for readability.

- `BOOTSTRAP_INSPECTION_COMPLETE`; PID **19652**, host UID **11064**.
- Installed Minecraft **1.26.50.4 / 972605004**, **1 APK**, supplied engine hash matched.
- Real `com.mojang.minecraftpe.MainActivity` directly extends **`com.google.androidgamesdk.GameActivity`**.
- **35 native declarations**, not truncated. No declaration was invoked.
- `<clinit>`: **45 code units / 90 bytes**, fully extracted and locally instruction-scanned.
- Source: `classes3.dex`, **9,336,804 bytes**, ZIP CRC **1326635678**. A CRC is provenance, not cryptographic authentication.

## What changed in our understanding

The actual MainActivity has its own `System.loadLibrary` calls, in this bytecode order:

1. `c++_shared`
2. `maesdk`
3. `HttpClient.Android`
4. `PlayFabMultiplayer`
5. `fmod`
6. **`minecraftpe`**

The method also has two logging paths and branches. The host 0.3 report did not extract exception-table semantics: do not infer that every dependency load completed, or that failures cannot be caught, from this static sequence.

This favors letting the **original Java initializer** perform JVM-managed loading, using one real guest class loader. Calling the engine's `JNI_OnLoad` manually first would be the wrong default: it would bypass normal JVM native-library ownership and could duplicate initialization.

## Boundary and next step

This report executed **no Minecraft Java/native initialization** and created no Activity. The direct superclass's initializer was not included; its installed DEX must be checked, not guessed from an upstream SDK version. Framework/AndroidX ancestor and other reachable SDK initializers are not exhaustively audited.

Host 0.4's consent-gated experiment checks the known MainActivity code/references and inspects the actual GameActivity before initialization. If GameActivity has any unreviewed `<clinit>`, it saves the metadata and stops. Otherwise it can initialize the real class through ART on the private process's main looper. See [JVM_INITIALIZATION.md](JVM_INITIALIZATION.md). Real device JVM initialization and the Gate 2 title screen remain pending.
