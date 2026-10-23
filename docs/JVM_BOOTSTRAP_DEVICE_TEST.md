# Bedrock bootstrap — successful user-reported phone result

## Evidence

The user reports **PID 12037 / UID 11072**, Minecraft Bedrock for Android **1.26.50.4 / 972605004**, one APK, engine hash matched, and:

```text
JVM_BOOTSTRAP_RETURNED (not game startup)
Real MainActivity class initialization returned; JVM-managed native loads used the same guest loader.
ENGINE_ALREADY_MAPPED (RTLD_NOLOAD lookup only)
JNI_OnLoad owner: [installed com.mojang.minecraftpe]/lib/arm64/libminecraftpe.so
Mapping base (this run only): 0x7169c74000
```

[Normalized report](JVM_BOOTSTRAP_DEVICE_TEST.json) retains the library lookup sequence and full GameActivity metadata while omitting random installation-directory components. This is a user-pasted report, not independent phone access. It follows delivery of host **0.4.3**; the pasted output itself does not include a host build identifier. Android 13 is the previously reported environment, not a newly extracted OS fingerprint.

## What this establishes

- The real Bedrock Android MainActivity class initializer **returned**, rather than stopping at a metadata gate or disconnecting during library loading.
- The selected installed engine matched the supplied fingerprint; no imported duplicate was needed.
- Original class initialization and JVM-managed loading used one guest loader. Lookups include all six original dependencies/engine in order: `c++_shared`, `maesdk`, `HttpClient.Android`, `PlayFabMultiplayer`, `fmod`, `minecraftpe`; our diagnostic bridge follows.
- The post-initialization `RTLD_NOLOAD` observation found the already mapped installed `libminecraftpe.so` and its `JNI_OnLoad` symbol owner. It did not invoke that symbol or a game entrypoint.
- GameActivity again has the reviewed zero-register, one-unit `000e` no-op initializer, AppCompatActivity superclass, and 24 native declarations in `classes2.dex` (1,655,112 bytes, CRC 3014425259).

This is materially stronger than the earlier link-only result: **the original class-initialization/native-loading path completed as reported**. The missing-network-state abort did not prevent this attempt returning. ART mediates JNI callbacks during original loading; the probe does not separately measure the engine callback's return value or call count. It is neither necessary nor appropriate to manually invoke it now.

## Remaining boundary

No original Minecraft Activity instance, Application/provider environment, lifecycle, Surface or title screen was created. This is not Minecraft Java Edition and does not use a desktop Java game runtime: Java/JNI here is Bedrock Android's wrapper around its native engine.

A returned bootstrap is not proof of subsequent SDK/game-loop stability, all native bindings, legitimate entitlement handling or access to the other installed app's private worlds/account data. The mapping base is transient evidence, **not a reusable address**. The probe is deliberately cleaned up after reporting; it is not a persistent game session to attach an Activity to later.

**Bootstrap substep: complete at this user-reported functional level. Gate 2 (hosted Bedrock title screen): incomplete.**

## Next work

Proceed to the [bounded Activity-hosting stage](ACTIVITY_HOSTING.md): actual manifest/startup requirements, resources/context, framework-created Activity and lifecycle/Surface. Do not ask the user to repeat the cooperative demo, link, static bootstrap or successful class-initialization test. No new APK is produced solely to record this result.
