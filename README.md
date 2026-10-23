# Client Base

A clean Android hosting prototype for an independent, rootless client. The intended target is **Android 13 / Minecraft 26.50**, with the original installed Minecraft APK left unchanged.

**Current implementation: a host plus a cooperative demo guest, not a working Minecraft launcher.**

## Documentation

- [How we think the original client works](docs/THEORY.md) — evidence, hypotheses and unresolved engine integration.
- [Current implementation and roadmap](docs/STATUS.md) — what is implemented versus tested, and our next acceptance gates.
- [First phone-test result](docs/DEVICE_TEST.md) — initial load, repeated loads and rotation passed as reported; cooperative-demo gate complete.
- [Build and install APKs from your phone](docs/BUILDING.md).
- [Engine phone-test result](docs/ENGINE_DEVICE_TEST.md) — linking passed as reported for the hash-matched Minecraft 1.26.50.4 engine; JNI/game startup pending.
- [Try the supplied engine](docs/ENGINE_PROBE.md) — optional hash-checked import and private-process link diagnostic.
- [JNI bootstrap inspection](docs/JNI_BOOTSTRAP.md) — native prerequisites and the no-execution installed-DEX diagnostic.
- [Engine input provenance](docs/ENGINE_INPUT.json).

## Repository layout

| Path | Purpose |
| --- | --- |
| `host/` | Launcher, demo loader, engine import/link probe, static bootstrap inspection and Android tests |
| `demo-guest/` | Separate demo app with guest resources and a tiny native library |
| `contract/` | Cooperative Java interface shared by both apps |
| `.github/workflows/build-apk.yml` | Builds debug APKs; runs Android 13 emulator tests |
| `tools/test_client_base.py` | Local source guards and Linux native identity test |
| `libminecraftpe.7z` | Retained engine input; **not included in either APK** |

The previous archives, Kotlin feature files, reverse-engineering reports, fixtures and trace tools were removed from the current tree as requested. This is a normal repository cleanup, not a rewrite of Git history.

## First milestone

Install the host and demo APKs from the **same workflow run**. Open **Client Base** and tap **Load trusted demo**. The intended result is guest code/resources/JNI running under the host's PID and UID, despite the guest being a separate installed app. See [the test instructions](docs/BUILDING.md#phone-only-installation).

Passing this milestone proves a cooperative plugin can execute in the host. It does **not** establish Minecraft lifecycle virtualization, engine loading, hooks, entitlement compatibility or access to Minecraft's private worlds/account data.

The host now offers an explicit, fingerprint-pinned engine import/link experiment; it does not start the game. Both apps currently request no permissions. Guest code is **not sandboxed from the host**; the loader accepts only our fixed, same-signed demo. No ProtoHax runtime, Minecraft engine/content, private keys or credentials are packaged into the debug APKs.
