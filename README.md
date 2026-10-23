# Client Base

A clean Android hosting prototype for an independent, rootless client. The intended target is **Android 13 / Minecraft 26.50**, with the original installed Minecraft APK left unchanged.

**Current implementation: hosting prototype with a successful reported Bedrock bootstrap and a new experimental Activity adapter; not a proven Minecraft launcher.**

**Latest phone result:** [Bedrock bootstrap returned successfully](docs/JVM_BOOTSTRAP_DEVICE_TEST.md) (PID 12037 / UID 11072). Original MainActivity initialization completed with JVM-managed native loading and the hash-matched engine already mapped. No Activity/title screen yet. **Do not repeat the bootstrap test**; the [host 0.5.0 Activity adapter](docs/BEDROCK_ACTIVITY_ATTEMPT.md) is now implemented and awaiting Android validation and its first phone result.

## Documentation

- [Original Bedrock Activity attempt](docs/BEDROCK_ACTIVITY_ATTEMPT.md) — dedicated-process adapter, exact startup pins, consent and separate diagnostics.
- [Activity bridge implementation](docs/ACTIVITY_BRIDGE.md) — framework Activity/resources/lifecycle/Surface work and automated coverage; not yet a Minecraft-launch build.

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

The host offers a fingerprint-pinned engine import/link experiment and a separate consent-gated JVM initialization experiment using the hash-identical installed engine. The latter executes original Java/native initialization, but creates no Minecraft Activity or title screen. Host 0.5.0 requests normal `ACCESS_NETWORK_STATE` and `INTERNET` for the original networked Activity/SDK startup. No runtime permission dialog is needed. The AppCompat developer fixture uses its own app-specific signature permission for private receivers; no other Android platform grant is added. Guest code is **not sandboxed from the host**; the loader accepts only our fixed, same-signed demo. No ProtoHax runtime, Minecraft engine/content, private keys or credentials are packaged into the debug APKs.
