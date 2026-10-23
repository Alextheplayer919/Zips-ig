# 0.6.3 integration preview — registered, not live gameplay modules

The user asked to add the missing integration pieces ourselves. This is a bounded implementation step, **not completion of working Fly/KillAura** and not another request for the old project.

## Implemented

- A native C++ registry declares **MotionFly / Movement** and **KillAura / Combat**. Java build-time registrations show both in the existing overlay, with their exact missing-live-backend status and **no fake interactive ON switches**. Visual/Misc stay empty. Memory/report/copy remain outside the overlay. These entries are build-time metadata, not dynamic capability discovery or native IPC acknowledgement.
- `FlyPacketRuntime` implements decoded packet dispatch and session lifecycle around the tracker/Fly policy: StartGame before enablement; update self from AuthInput before calculating motion; reject wrong executor/invalid state; server self-correction tracking; respawn/dimension/disconnect reset and disable; incompatible/closed sessions rejected. Only outgoing AuthInput can cause a Fly motion injection. Unrelated packets are left alone. Output declares original forwarding, never cancels/edits the original; **the actual transport is still responsible for forwarding it once**. Motion sends use the separate `PacketSession.clientBoundMotion` adapter and failure/exception disables Fly. CollisionGuard and MovementCompliance stay removed.
- `KillAuraRuntime` implements native-adapter binding, exact supplied engine-build gate, game-thread requirement, explicit enable/disable, tick scheduling, world/player identity checks, target revalidation before each attack, attack/swing execution via native adapter, and disconnect/error/reentrant-stop handling. It has **no packet/proxy interface**. Adapters must re-resolve IDs and check real actor validity at the actual call; they must remain alive during callbacks and detach on their executor before destruction.
- Runtime factories and policy code are now part of the Android NDK build through `modules/module_runtime.cpp`, not only host compiler tests. They are not automatically attached to Minecraft. Melody MIT notice is included in APK assets because its adapted logic is now compiled.
- 0.6.3/code16 is a **registration/runtime preview**, not a completed feature release. Original engine, startup/lifecycle, UI-process separation, permission/foreground Stop flow and read-only memory opcode remain unchanged. No external socket, auth request, new permission, guessed engine offset or renderer hook is installed.

## Real integration still outstanding

`PacketSession` and `NativeCombatAdapter` are abstract interfaces, not concrete implementations. There is still no live RakNet/Bedrock decoder/encoder, session/connection hookup, native player/world provider, game tick hook or verified attack/swing ABI. A decoded event runtime cannot intercept encrypted/compressed traffic by itself. A native callback contract cannot discover engine functions by itself. Registering the modules is not evidence that either can function in-game. No account data or raw addresses can be supplied through the UI to bypass this.

A targeted static check of the **exact original engine** is recorded in [ENGINE_MODULE_ABI.json](ENGINE_MODULE_ABI.json): 26 ELF sections, no full symtab, 90,653 defined dynsym symbols, but none of the searched player/GameMode/packet/RakNet name fragments. This shows direct lookup under those names is unavailable, **not** that the engine lacks those internal functions. We did not call or guess any address or reuse Windows layouts. Internal integration still requires bounded ARM64 analysis and real-device verification; it is not solved by this preview.

## Validation

32 local source/native tests pass. The new runtime executable tests session/tracker ordering, untouched-original disposition, Fly-only injection/direction, wrong executor, invalid inputs, failed/throwing sends, world reset/reconnect, build/thread gating, native attack/swing actions, disappearing targets, errors and stopping during callbacks. Real ASan/UBSan execution also passes. The packet/combat adapters used in those tests are clearly test fixtures, **not production implementations**.

New Android regressions check immutable category registration/readiness labels and retained separate diagnostics. The actual overlay test checks each registered row exists and is not clickable. Existing mapped-memory/native/IPC/overlay/startup coverage remains. Android build/emulator validation passed for this registration/runtime preview below; no finished gameplay-module APK is claimed.

## Validated preview artifact

[Android 13 CI run 40006774087](https://github.com/Alextheplayer919/Zips-ig/actions/runs/40006774087) **passed**, job **120597247317**, **7m24s**, app/test **4f7dec6339a61c7b8876f767464aec10c64f3127**, host **0.6.3 / code16**. Android NDK ARM64/x86_64 build and APK exclusion checks passed, as did the full **62-case Android13 x86_64 suite**. **32 local source/native tests** pass; the runtime regression also passed local ASan/UBSan. Original ARM64 Minecraft is not executed in CI. Report XML not parsed locally; successful Actions job is the validation source.

- [Optional registration-preview APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/40006774087/artifacts/12524767242): **client-base-debug-102**, **6,255,189 bytes**. Install only **client-base-host-debug.apk**. **This does not enable working Fly/KillAura.** Both rows accurately show unavailable. Keep the preceding 0.6.2 APK if you do not need to inspect registrations; no Minecraft reimport/bootstrap repeat.
- [Device reports](https://github.com/Alextheplayer919/Zips-ig/actions/runs/40006774087/artifacts/12524183914): **client-base-device-tests-102**, **384,836 bytes**.
- Unchanged archive blob **c78f6e8642d334916d247487897eb871ad27c160**. Startup/wrapper/SDK, memory IPC and overlay service unchanged. Working 0.5.4/0.6.1 references preserved. PR#1 remains open, no merge/release/history rewrite.
