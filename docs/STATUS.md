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

### Validation boundary

Eight local checks passed before and after the repository migration. These are source guards plus an actually compiled/executed **Linux** native PID/UID test. The six Java sources also passed syntax parsing before migration; the Android source was moved unchanged.

The local sandbox does not have an Android SDK and cannot fetch the Gradle distribution. Therefore local checks are **not** evidence of a successful Android build or device run. Check the [workflow runs](https://github.com/Alextheplayer919/Zips-ig/actions) for actual compilation, artifact and emulator outcomes. A workflow definition alone is not a passing test.

**CI result:** APK compilation and Android 13/API 33 x86_64 integration passed in [run 39840759440](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39840759440). The user subsequently reported a successful first phone load; see [the device test record](DEVICE_TEST.md). Repeated phone loads and Activity recreation remain unreported.

Two Android instrumentation tests are supplied:

1. Guest class-loader identity, JNI PID/UID matching the host, different installed guest UID, and guest resources.
2. Actual load-button behavior and reloading after host Activity recreation.

### Not implemented

- Minecraft package discovery/launch or a general guest Activity/Application/provider environment.
- Minecraft native-engine startup, resolver reconstruction or hooks.
- Access to the separately installed game's private data.
- Game features/modules or an update-compatible engine integration layer.

The original client's successful Android 13 / Minecraft 26.50 launch is **user-reported**. It is not a test result for our base. The supplied engine library's version is independently unverified.

## Gate 1 — cooperative demo on Android 13

**Current result:** initial phone load passed as reported: native PID/UID match the host, and the installed guest UID differs. The complete gate remains pending the repeated-load and rotation checks on that phone.

**Pass:** both same-build APKs install; the host loads the guest resource/View; native PID/UID equal host PID/UID; installed guest UID differs; repeated loads and Activity recreation work. Confirm on the user's ARM64 phone, not just an x86_64 emulator.

**Fail:** fix the specific class-loading, signing, resource, ABI or native-path problem. Do not add Minecraft hooks or restart protected-VM trace expansion to compensate.

## Gate 2 — hosting-only Minecraft adapter

After Gate 1, build a separate adapter for the original installed base/split APKs. Do not loosen the demo trust policy into an unrestricted plugin loader; Minecraft requires its own explicit package/trust and lifecycle design.

**Pass:** the original game reaches its title screen in the intended host process on Android 13 / target Minecraft 26.50, with package/lifecycle/native-load behavior recorded. No gameplay features or binary patches are required for this gate.

**Blockers must be explicit:** identity/signature assumptions, missing dependencies/splits, native startup, lifecycle failures or legitimate entitlement requirements. A loading screen or a mapped library alone is not a pass.

## Gate 3 — controlled native integration

Only after hosted Minecraft runs, establish engine identity/mapping inside our own process and determine the minimum interfaces needed. Begin with read-only diagnostics. Choose a hook technique only when the actual integration point is understood.

No compatibility, engine address, native binding or hook is assumed from old traces, a string marker or a large test count.
