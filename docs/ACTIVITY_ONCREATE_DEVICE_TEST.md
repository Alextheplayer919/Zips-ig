# Original onCreate returned; our nested Surface callback crashed

## Phone result

The user supplied the missing tail of the host 0.5.0 Activity attempt: **PID 17899 / UID 11075**, `dev.zips.clientbase.host:game_host`, SDK **33**. [Normalized selected evidence](ACTIVITY_ONCREATE_DEVICE_TEST.json) excludes install tokens, device identifier, session values and SDK configuration/API-key text. No raw whole-device trace is committed.

Startup-file and engine pins passed. The log then records **Minecraft's own `JNI_OnLoad completed`**, GameActivity native registration and **`android_main starting`**, with data paths under the host's storage. Original MainActivity `onCreate` **returned**. This is a substantial advance beyond class initialization alone, not title-screen proof. The engine-emitted JNI log is positive evidence beyond the earlier lookup-only reports, but the host does not separately instrument its callback count/return value.

Android reported **JAVA_CRASH (4)**, status 0, cleanup **none**. The main-thread fatal exception is:

```text
IllegalAccessError: Illegal class access:
MinecraftHostedActivity attempting to access MinecraftHostedActivity$1
at MinecraftHostedActivity.observeSurfaces:42
at MinecraftHostedActivity.observeSurfaces:50 (recursive traversal)
at MinecraftHostedActivity.onCreate:37
```

This is **our observer's loader bug after the original onCreate returned**, not a missing/imported `.so`, proven native engine abort, or controller cleanup. The exact root wrapper was child-owned, but its `$1` anonymous callback fell through to the parent. Java package-private/private synthetic access requires a common defining loader as well as matching package names.

The log also shows an AppCompat component metadata lookup failing for the actual wrapper name, and a Swappy in-memory-loader library lookup error. The fatal stack is neither of those; do not disable Swappy or infer that rendering already works. They remain separate observations, with the component naming mismatch corrected below.

## Host 0.5.1 correction

- Child ownership covers **exact roots plus their `$` nested/synthetic families** for the wrapper, bridge and production Surface observer. It does not sweep all host classes into the guest loader; shared contracts remain parent-owned.
- Extract the passive observer into `HostedSurfaceObserver`, with a real anonymous callback/private outer access pattern that can be exercised in CI **without any Minecraft class or fake game implementation**. Continue actual Surface observation instead of deleting the feature to hide the error.
- Register **the actual `MinecraftHostedActivity` name** in the host manifest and use that string for factory dispatch/launch. AppCompat's class-derived host ComponentName now resolves to real metadata, not a renamed placeholder. It remains non-exported in the same dedicated `:game_host` process; no PackageManager spoofing.
- Compact the Activity timeline: retain stages and each newest library lookup, not repeated full GameActivity DEX dumps. Keep exception/exit diagnostics intact.
- Add **Save Bedrock Activity report (.txt)** using Android's system document picker, so another clipboard truncation/empty file is not necessary. No storage permission is added. The file picker/export is compiled/source-checked, not an independently captured phone export yet.
- Keep version, all startup/engine pins, original library order, ART JNI ownership, CheckJNI, current permissions, lifecycle and diagnostic bounds unchanged.

Regression coverage loads the actual observer and callback in a child loader **after the parent copy exists**, invokes callback/private outer access, checks exact family boundaries/shared contract identity, and exercises the production observer against a real framework-delivered AppCompat Surface. It also checks compact stages and actual wrapper component registration. These tests do not execute original ARM64 Minecraft.

Android validation must be recorded before delivery. Next phone attempt is one **Launch Bedrock Activity (experimental)** with the corrected host only, Minecraft unchanged. No engine import, demo/link/bootstrap repeat or additional input file is required. Report actual visible output and save/share the Activity report. Gate 2/title screen remains incomplete.
