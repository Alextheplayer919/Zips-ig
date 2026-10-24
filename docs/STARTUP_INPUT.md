# Supplied startup input: dexes.7z

## Input located and inspected

The user directed us to `dexes.7z`, present on GitHub `main`, Git blob **`c402a77d42600d3db046bed81517725077bb2b8c`**. The archive is **5,913,382 bytes**, SHA-256 **`44e99c0a0f06bc662ff8f6654dc4124db7a6164b06e6ccbe94a7e4aa50c9f594`**. [Normalized static audit](STARTUP_INPUT.json) includes individual file hashes and selected manifest metadata.

Downloaded/extracted only into ignored `.cache/startup-input/`; no merging `main`, restoring legacy archives, committing Minecraft DEX/assets, or executing game code. Archive entries were checked for relative safe paths, no symlinks, and a total uncompressed size below 64 MiB before extraction.

| Archive entry | Bytes |
| --- | ---: |
| `dexes/AndroidManifest.xml` | 27,172 |
| `dexes/classes.dex` | 6,905,400 |
| `dexes/classes2.dex` | 1,655,112 |
| `dexes/classes3.dex` | 9,336,804 |
| `dexes/classes4.dex` | 1,802,892 |
| `dexes/classes5.dex` | 36,436 |
| `dexes/resources.arsc` | 277,104 |

This contains the requested startup-review inputs. No additional upload is needed for the initial bounded Activity startup audit. It is not a complete installable APK or a substitute for the installed game's assets during runtime hosting.

## Identity and integrity checks

- Decoded binary manifest identifies **`com.mojang.minecraftpe`**, **versionName `1.26.50.4` / versionCode `972605004`**, matching the phone's target.
- `classes2.dex` size and CRC32 **3014425259** match the phone's GameActivity report. `classes3.dex` size and CRC32 **1326635678** match the earlier MainActivity report.
- All five DEX headers declare the observed file size, and their Adler-32 checks match.
- **Caveat:** stored DEX header SHA-1 fields in `classes2.dex` and `classes4.dex` do not match recomputed SHA-1. Cause unknown. Keep the supplied bytes unchanged; do not silently repair, call this a full integrity pass, or infer tampering from this alone. The phone size/CRC cross-check still holds for `classes2.dex`.
- The extracted bundle has no APK signature/signing block. Matching version strings and two phone CRCs do **not** authenticate the original APK or all Java code. Existing runtime fingerprint/code gates must not be weakened on this basis.

## First concrete Activity-hosting facts

These are manifest declarations, not proof that an adapter has implemented them:

- Application: **`android.app.Application`**, not a guessed custom Minecraft Application class.
- AppComponentFactory: **`androidx.core.app.CoreComponentFactory`**.
- Main launcher Activity: **`com.mojang.minecraftpe.MainActivity`**, theme **`0x7f0e0007`**, launchMode **2 (`singleTask`)**, screenOrientation **6 (`sensorLandscape`)**, configChanges **`0x40001fff`**.
- Activity metadata **`android.app.lib_name = minecraftpe`**.
- Providers: AndroidX FileProvider, PlayGamesInitProvider, FirebaseInitProvider and AndroidX InitializationProvider. The latter declares EmojiCompat, ProcessLifecycle and ProfileInstaller initializers. Do not blindly copy authorities or run all providers as a claimed startup fix.
- Original manifest declares min SDK **29** and target SDK **36**. These describe the supplied game, **not a change to our Android 13 test target or the host's target SDK 33**.
- Original network-security config references **`0x7f100003`**. This bundle includes the resource table, not all referenced `res/` payloads or game assets.

Next: use these actual declarations and bounded MainActivity/GameActivity lifecycle inspection to implement the [Activity-hosting stage](ACTIVITY_HOSTING.md). No new APK or successful Activity launch is claimed by this input audit, and no host permission is added.
