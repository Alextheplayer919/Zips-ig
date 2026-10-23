# Client Base

A clean Android hosting prototype for an independent, rootless client. The intended target is **Android 13 / Minecraft 26.50**, with the original installed Minecraft APK left unchanged.

**Current implementation: rootless Bedrock hosting prototype with a fully functioning launch confirmed by the user. Read-only loaded-engine memory inspection is implemented; player-field integration and hooks are not.**

**Latest phone result:** **[The user confirms a fully functioning hosted launch](docs/HOSTED_LAUNCH_SUCCESS.md) after host 0.5.4.** This is the first reported working Bedrock launch, not merely onCreate/Surface progress. Preserve app/test commit `287e04b` and [the validated 0.5.4 APK](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39988805056/artifacts/12516682587) as the working baseline. The confirmation is user-reported, without a new PID/UID or capture; it does not establish every SDK feature or long-session stability. No engine reimport or repeated bootstrap/demo test is needed. Player-field integration/hooks remain a later development stage, not a completed feature.

**Validated read-only foundation — 0.6.0:** [external controls and read-only memory IPC](docs/MEMORY_CONTROLS.md). The GUI stays in the host UI process and a separate task; no menu is rendered inside Minecraft. Manual snapshots inspect the already-loaded engine header/build ID in `:game_host`. Split-screen is an optional device-dependent route to keep both visible, not a promise of background ticking. [Android 13 build/APK and 54-case regressions passed](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39993673423); [host 0.6.0 APK](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39993673423/artifacts/12518682891). keep 0.5.4 as the reported-working baseline. No player offsets, writes, hooks or fake module toggles.

**Working overlay foundation — 0.6.1:** [Java external click GUI](docs/EXTERNAL_OVERLAY.md), implemented over the same private read-only IPC. Explicit Android permission, bounded draggable non-focusable chip/panel, foreground-service Stop and permission-revocation cleanup. Separate Controls remains the permission-free fallback. [Android 13 build and full 59-case emulator suite passed](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39998204360); [download host 0.6.1 APK](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39998204360/artifacts/12520922597). Minecraft focus/performance compatibility still requires a phone test. No Kotlin/Compose or game-renderer hooks.

**Latest — 0.6.2 category click GUI:** [Movement / Combat / Visual / Misc](docs/CLICK_GUI.md), no fake module toggles. Memory refresh/copy/report options are outside the floating menu in Controls; the user reports the preceding overlay works on their phone. [Android build/APK and full 60-case emulator suite passed](https://github.com/Alextheplayer919/Zips-ig/actions/runs/40000628335); [download host APK](https://github.com/Alextheplayer919/Zips-ig/actions/runs/40000628335/artifacts/12522700433). No extra upload is needed for this UI. Next module work starts with a concrete module/interface, not guessed offsets or another giant dump.

**Module work in progress:** [Melody KillAura adaptation + supplied MotionFly](docs/MODULE_PORTS.md). Portable selection/movement policies and the supplied tracker’s self-state subset are adapted and host-native tested; collision/adaptive-governor helpers are removed at the user’s request; **not yet connected to Minecraft or a packet transport**, so there are no working module toggles/new module APK yet. Custom packet handling is scoped to Fly only.

**0.6.3 integration preview in development:** [registered module rows + decoded packet/native runtime drivers](docs/MODULE_RUNTIME.md). **No live transport or verified native engine adapters yet**; rows show unavailable, not working toggles. Android validation pending.

## Documentation

- [Original Bedrock Activity attempt](docs/BEDROCK_ACTIVITY_ATTEMPT.md) — dedicated-process adapter, exact startup pins, consent and separate diagnostics.
- [Activity bridge implementation](docs/ACTIVITY_BRIDGE.md) — framework Activity/resources/lifecycle/Surface foundation; see the original-game attempt above for the current build.

- [How we think the original client works](docs/THEORY.md) — evidence, hypotheses and unresolved engine integration.
- [Current implementation and roadmap](docs/STATUS.md) — what is implemented versus tested, and our next acceptance gates.
- [First phone-test result](docs/DEVICE_TEST.md) — initial load, repeated loads and rotation passed as reported; cooperative-demo gate complete.
- [Build and install APKs from your phone](docs/BUILDING.md).
- [Engine phone-test result](docs/ENGINE_DEVICE_TEST.md) — earlier link-only result for the hash-matched Minecraft 1.26.50.4 engine; superseded for bootstrap by the successful report above.
- [Try the supplied engine](docs/ENGINE_PROBE.md) — optional hash-checked import and private-process link diagnostic.
- [Phone Java bootstrap result](docs/BOOTSTRAP_DEVICE_TEST.md) — original MainActivity loads the dependencies and engine itself.
- [Confirmed permission abort and correction](docs/NETWORK_PERMISSION_DEVICE_TEST.md) — host 0.4.3 adds the missing network-state permission and checks it before JNI.
- [Initialization disconnect / exit diagnostics](docs/EXIT_DIAGNOSTICS.md) — host 0.4.2 added own-process exit reasons and available crash evidence; its phone report identified the permission failure above.
- [GameActivity phone preflight](docs/GAMEACTIVITY_DEVICE_TEST.md) — empty superclass initializer identified; host 0.4.1 corrects the overly strict gate.
- [Successful Bedrock bootstrap](docs/JVM_BOOTSTRAP_DEVICE_TEST.md) — original class initialization returned; Activity hosting remains next.
- [Controlled Java/JNI initialization](docs/JVM_INITIALIZATION.md) — host 0.4 experiment; not an Activity launch.
- [JNI bootstrap inspection](docs/JNI_BOOTSTRAP.md) — native prerequisites and the no-execution installed-DEX diagnostic.
- [Engine input provenance](docs/ENGINE_INPUT.json).

## Repository layout

| Path | Purpose |
| --- | --- |
| `host/` | Launcher, demo loader, engine import/link probe, bootstrap inspection/initialization and Android tests |
| `demo-guest/` | Separate demo app with guest resources and a tiny native library |
| `contract/` | Cooperative Java interface shared by both apps |
| `.github/workflows/build-apk.yml` | Builds debug APKs; runs Android 13 emulator tests |
| `tools/test_client_base.py` | Local source guards and Linux native identity test |
| `libminecraftpe.7z` | Retained engine input; **not included in either APK** |

The previous archives, Kotlin feature files, reverse-engineering reports, fixtures and trace tools were removed from the current tree as requested. This is a normal repository cleanup, not a rewrite of Git history.

## First milestone

Install the host and demo APKs from the **same workflow run**. Open **Client Base** and tap **Load trusted demo**. The intended result is guest code/resources/JNI running under the host's PID and UID, despite the guest being a separate installed app. See [the test instructions](docs/BUILDING.md#phone-only-installation).

Passing this milestone proves a cooperative plugin can execute in the host. It does **not** establish Minecraft lifecycle virtualization, engine loading, hooks, entitlement compatibility or access to Minecraft's private worlds/account data.

The host offers a fingerprint-pinned engine import/link experiment and a separate consent-gated JVM initialization experiment using the hash-identical installed engine. The latter executes original Java/native initialization, but creates no Minecraft Activity or title screen. Host 0.5.x requests normal `ACCESS_NETWORK_STATE` and `INTERNET` for the original networked Activity/SDK startup. No runtime permission dialog is needed. The AppCompat developer fixture uses its own app-specific signature permission for private receivers; no other Android platform grant is added. Guest code is **not sandboxed from the host**; the loader accepts only our fixed, same-signed demo. No ProtoHax runtime, Minecraft engine/content, private keys or credentials are packaged into the debug APKs.
