# Supplied engine: opt-in linkage probe

## What we learned from the actual file

The supplied SHA-256-verified `libminecraftpe.so` is ARM64 ELF64, with `JNI_OnLoad` and Minecraft JNI exports. Static inventory: [ENGINE_LINKAGE.json](ENGINE_LINKAGE.json). Its dynamic dependencies include five non-system libraries **not contained in the supplied archive**:

- `libPlayFabMultiplayer.so`
- `libfmod.so`
- `libHttpClient.Android.so`
- `libmaesdk.so`
- `libc++_shared.so`

The ELF also declares 18,624 bytes of initializer-array slots (2,328 eight-byte slots). Loading it is not equivalent to reading inert data: the dynamic linker executes constructors, potentially in its dependencies too.

A library alone is not a complete game. Exported JNI names do not establish callable signatures, an initialized Activity or a working game lifecycle. The [user-reported phone result](ENGINE_DEVICE_TEST.md) now matches its hash to installed versionName **1.26.50.4**, versionCode **972605004**, and reports successful linking. JNI/game startup remain untested.

## Using the new APK on the phone

1. Download the new host/demo APK pair from the workflow artifact. Host version is **0.2.0**. Debug signing keys can change between runs; if Android rejects an update, uninstall **our host/demo only**, then install the matching pair. Do not uninstall Minecraft.
2. Have your trusted Minecraft installation present. The probe reads its package/code paths, not its private worlds or account data.
3. Open **Client Base**, tap **Import supplied engine file**, and choose the exact `libminecraftpe.7z` from this repository, or its extracted `libminecraftpe.so`. Use the file chooser; no broad storage permission is requested.
4. Wait for hashing/extraction. Allow roughly 400 MB free space, more if retaining/replacing an existing input; decompression uses a 64 MiB dictionary and is capped at 256 MiB decoder memory. Incorrect input is rejected, and the compressed fingerprint is verified **before** 7z parsing.
5. Tap **Run engine link probe** and read the confirmation. It executes native code in a separate private host process. Keep the app open until a result appears; the 180-second limit starts with the probe, not during import.
6. Copy the engine-status text back here. The demo's ordinary `PASS` message is separate and does not describe the engine probe.

The archive and extracted engine are **not bundled in the APKs**. Import is local, app-private and fingerprint-pinned; no upload/network permission is involved.

## What the probe does

- Re-verifies the imported engine's exact hash and checks that its process is ARM64.
- Finds installed Minecraft's base/splits and reports actual `versionName`/`versionCode` without relabeling them as 26.50.
- Hashes the installed ARM64 engine. **If it differs or is absent, stop before executing game code.** This avoids mixing dependency paths from an unidentified game build.
- Creates a class-loader native namespace using our native bridge and the matching installation's native directory/APK library paths. No Minecraft Java class or Activity is instantiated.
- In `:engine_probe`, calls `dlopen(..., RTLD_NOW | RTLD_LOCAL)` on the imported engine. It can resolve dependencies from the matching installed game without copying private app data.
- Reports the linker error, or selected symbol owners/mapping bases if loading returns successfully. It never calls any resolved address.
- Does **not explicitly invoke** Minecraft's `JNI_OnLoad`, `ANativeActivity_onCreate`, native gameplay methods or hooks. `dlopen` itself runs ELF constructors; these can have their own initialization effects.
- Saves its last stage before native loading. On completion/timeout the binding is released and the one-shot service process exits; it does not invent a native shutdown/unload sequence. The UI binding survives Activity recreation.

The probe uses its own statically linked C++ runtime so it does not intentionally substitute the host's `libc++_shared.so` for the game's dependency.

## Interpreting results

| Result | Meaning |
| --- | --- |
| Import rejected | Wrong/corrupt file, resource/IO problem, or insufficient decoder memory; no engine execution |
| `PROBE_BLOCKED` | ABI, missing import/package, fingerprint mismatch or setup problem; see exact message |
| `LINK_CHECK_FAILED` | The dynamic linker returned an error such as a missing dependency, unavailable symbol or namespace restriction |
| `LINK_CHECK_SUCCESS` | Linking/constructors returned in the private host process; **not JNI initialization or Minecraft startup** |
| Disconnection/timeout | Native failure, OS termination, hang or another interruption is possible; last stage is evidence, not a definitive crash diagnosis |

A private process reduces direct crash coupling to the UI but **is not a security sandbox**: it shares the host UID/files/permissions, and system memory pressure can still affect the app. Only use the provided engine and a trusted installed game.

## Validation and boundaries

The existing demo remains separate. Added Android tests exercise malformed-input rejection, our own native bridge's successful/failing links, and actual import/hash verification of the supplied archive on an x86_64 Android 13 emulator. That last test treats the ARM64 library as data only; it must not execute it on x86_64.

The real engine `dlopen` requires the ARM64 phone with a matching Minecraft installation; the user has now [reported success](ENGINE_DEVICE_TEST.md). No local/emulator result should be described as successful Minecraft engine loading. The next full hosting gate still requires actual game Java/lifecycle/resources and a title screen.

For bounded offline inventory (no execution):

```sh
python -m pip install -r tools/requirements-engine.txt
python tools/inspect_engine.py /path/to/extracted/libminecraftpe.so
```
