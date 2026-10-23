# Next stage: a real Bedrock Android Activity environment

**Status: the prior framework bridge passed Android 13 integration. The [first original-game adapter](BEDROCK_ACTIVITY_ATTEMPT.md) is now implemented; its build/packaging and Android 13 checks passed; the first real-game Activity launch result is pending.** See [the first implementation and bounded startup findings](ACTIVITY_BRIDGE.md). The [phone bootstrap success](JVM_BOOTSTRAP_DEVICE_TEST.md) completes the class-initialization substep. The next acceptance target remains the original, unmodified Bedrock **1.26.50.4 / 972605004** title screen in our own process/UID on Android 13. No hooks or gameplay features in this stage.

## Startup input now available

The user supplied [`dexes.7z`](STARTUP_INPUT.md): all five DEX files, binary manifest and resource table. Manifest identity matches the target, and classes2/classes3 sizes and CRCs match the phone reports. The input audit records two DEX header SHA-1 discrepancies without altering files; APK signature authenticity is not established. Application/factory/provider and Activity metadata are now known from this manifest. No additional upload is needed for the initial startup audit. This does not implement Activity hosting.

## Known baseline

- Original MainActivity static initialization returned with JVM-managed loads and a hash-matched installed engine mapped.
- MainActivity extends the installed Google GameActivity, which extends AppCompatActivity. GameActivity has 24 declared native methods; its static initializer is a no-op.
- The engine exports GameActivity-related initialization symbols, but the correct Activity path must use the original Java lifecycle/native bindings, not a guessed function-pointer call.
- The probe creates no Activity/resources adapter, Application/providers or Surface. Its process is deliberately cleaned up after a result.

## 1. Prepare the actual startup contract

Keep investigation finite: inspect the installed package's manifest/component metadata and the relevant original Application/Activity startup methods, not a bulk whole-game trace. Record:

- Actual launch component/aliases, Application and AppComponentFactory, provider initialization requirements, required metadata, theme, orientation/configChanges and native-library metadata.
- Activity `onCreate` / superclass startup inputs and the resource/assets, directory and package-context calls on that path. The existing static-initializer scanner does not provide this audit.
- Dependencies that must exist before native Activity initialization. Treat missing Android capabilities or legitimate entitlement requirements as explicit blockers, not reasons to spoof grants or patch checks.

Use original public APK/resources and host-owned storage. Do not read the installed game's private data or assume its UID/permissions transfer. Do not silently change the target game build.

## 2. Build and test the host-side environment

- Select a real framework Activity creation path under a **host-declared component and host UID**, with the prepared guest loader. A host AppComponentFactory is a candidate creation seam, not yet a proven complete solution: Activity attachment, base context, package metadata and resources still require an Android 13-tested design.
- Preserve one guest class/native-library owner **within the hosting process**. A new persistent Activity process must prepare its own loader and initialization state; it cannot reuse the terminated probe's PID, loader, native pointers or mapping base. Do not initialize through one loader and instantiate through another.
- Supply actual game assets/resources and an appropriate theme/context. Test collisions between host/game app resource IDs and AndroidX/AppCompat expectations before calling the original Activity. A resource-only context is not proof that the original Application environment exists.
- Resolve required Application/provider setup and its order from the actual startup contract. Do not invent class names, blindly initialize every provider, or substitute empty successful callbacks.
- The supplied MainActivity shutdown path calls `System.exit(0)`. Put original-game Activity hosting in a dedicated host-owned game process, never the host UI process; do not suppress the original exit call.
- Keep the existing bounded initialization probe available separately. Persistent Activity hosting needs distinct lifecycle/cleanup ownership; the probe's normal-result termination cannot kill a running game.

Use our own small fixture first for framework-created guest Activity, resources/theme, lifecycle, Surface and same-loader native ownership. This is developer regression coverage, **not another request for the phone user to redo the completed demo**. If Android 13 requires a hidden-API-dependent approach, document and test that blocker rather than assuming reflective access works.

## 3. Controlled original Activity startup

Only after the environment is ready, add explicit launch consent and preserve identity/hash checks and own-process exit diagnostics. Let Android/original code deliver Activity creation, start/resume, focus, Surface creation/changes, pause/stop and destruction. Never call `new MainActivity()` plus `onCreate()` as a substitute for framework attachment, or invoke `initializeNativeCode` / `GameActivity_onCreate` by guessed addresses/signatures.

The first real-game attempt should distinguish: environment prepared → original Activity creation entered/returned → lifecycle/Surface reached → visible game output → title screen. Report the earliest concrete failure with bounded evidence; no automatic crash/relaunch loop. A returned `onCreate`, loading image or mapped engine alone does not pass Gate 2.

**Acceptance:** user-reported visible Bedrock title screen plus host-process identity and startup/lifecycle evidence. No private-world migration, engine hooks, account bypass or broader module work is part of this gate. Additional permissions must be justified by the actual path; this plan does not add any.
