# Selected modules: native KillAura + supplied packet-based MotionFly

## Scope confirmed by the user

Use the KillAura from the newly supplied `Melody-1.20.51.zip`, adapt it to **Minecraft Bedrock Android26.50**. Keep **MotionFly's supplied packet-driven behavior**, with custom packet handling **only for Fly**. Do not turn KillAura into a proxy module or copy Windows bindings into Android. Existing game rendering/lifecycle, startup pins and working overlay remain untouched.

Fetched the exact Melody blob from repository main via GitHub API; this did not switch branches or restore main's legacy tree. Archive and extracted review files remain ignored under `.cache/module-inputs/`. Input hashes/provenance are in [MODULE_INPUTS.json](MODULE_INPUTS.json). The archive is a Windows C++ project: its memory helper calls `GetModuleHandleA("Minecraft.Windows.exe")` and uses MinHook/Windows signatures. Those bindings, layouts, packet structs and vtable indices are not Android26.50 SDK evidence. Melody's MIT attribution/license is retained in `third_party/melody/LICENSE`.

## Implemented source ports — not live modules yet

`host/src/main/cpp/modules/module_policies.h` is portable C++ logic, exercised by `tests/native/module_policies_test.cpp` through the existing source/native test runner.

**KillAura:** adapted Melody's nearest-target ordering, visible/wall range, single/multi selection, mob option, hurt-time filter and tick cadence. Corrected the source's assignment-in-condition mode bug (`Mode = 1` / `Mode = 0`), added invalid-state/finite-value checks, friend/self exclusion, duplicate suppression and world/session reset. Inputs are typed entity snapshots; outputs are session-scoped native attack intents, not raw pointers or packets. The native adapter must obtain real attackability/visibility/player state and re-resolve/revalidate targets on the game thread before using the actual engine attack/swing functions.

**Not carried over from Melody:** direct packet rotations, manual Interact/equipment packet sends, old Windows SDK addresses/signatures, silent equipment switching and ImGui range/target renderer. In particular, its custom packet features conflict with the Fly-only packet scope. Ordinary Minecraft native attacks may naturally produce normal game traffic; “not proxy-based” does not mean the game sends no packets.

**MotionFly:** ported the provided speeds/defaults, input aliases, per-20 scaling, horizontal cap, yaw rotation/normalization, ascent/descent factors, glide and 4.5-block displacement rebase. It accepts only the semantic **client-to-server PlayerAuthInput** event while enabled. Its output is an intent for **client-bound SetEntityMotion**, matching the attachment's `session.clientBound(motionPacket)`; it does not rewrite outgoing position packets or implement a lagback/anti-cheat bypass. Other packet kinds/directions and disabled state produce no intent. Session/entity changes disable it.

### User refinement: remove guards, use supplied EntityTracker

The user explicitly considers `CollisionGuard` and `MovementCompliance` unnecessary and authorized their removal. **Both dependencies, all callback requirements and the adaptive setting are removed**, not replaced by dummy pass-through callbacks. MotionFly now outputs its calculated velocity directly. Its original speed defaults/cap and 4.5-block displacement skip remain. This intentionally drops the attachment's collision clamping and adaptive settling/governor behavior; no promise of collision safety, lagback prevention or server acceptance.

The new `EntityTracker.kt (1).txt` is recorded in input provenance. `self_tracker.h` adapts only the self-state subset needed by Fly: runtime/unique IDs, raw position/rotation, decoded StartGame, AuthInput and self MovePlayer, respawn/dimension changes, disconnect reset. The Kotlin account manager, inventory caches, coroutines, UI flows, entity-list cleanup, remote-agent feed and unrelated module references are not dependencies of this subset. **This is not a complete EntityTracker port and it does not feed proxy actor snapshots into native KillAura.**

The original code ignores server-boundary self MovePlayer corrections and leaves some origin flags/reset state stale; the subset accepts authoritative self corrections, validates finite values and direction, ignores other runtime IDs, retains explicit position-source tags without assuming an eye/feet offset, and issues new session generations at world/reconnect/respawn boundaries. Feed it on one packet/session executor, update the tracker **before** invoking Fly for AuthInput, and do not invoke Fly if the tracker rejects that update. The codec adapter must select the relevant respawn state. Keep tracker ownership across connections, reset on disconnect and disable Fly when tearing down/replacing the transport; methods remain decoded-state policy, not actual packet delivery.

> **Live internal integration (`0.6.4`):** [MODULE_RUNTIME.md](MODULE_RUNTIME.md) connects both modules inside the hosted ARM64 `1.26.50.4` game process (`LoopbackPacketSender` + `LegacyClientNetworkHandler` for MotionFly, `GameMode`/`SurvivalMode` + `LocalPlayer` + `ILevel` for KillAura) with backend-acknowledged `OFF` / `ARMED` / `ON` toggles in the external overlay.

## Historical port baseline (pre-0.6.4)

These are **logic ports, not completed working modules**. They are not connected to native Minecraft, compiled into its engine, exposed via JNI/IPC, installed as packet hooks, or shown as usable toggles. The subsequent preview registers unavailable rows only. No new module APK is being delivered. The last validated application remains 0.6.2; categories stay empty rather than pretending a backend exists.

KillAura still needs verified Android26.50 local-player/world/entity access, tick integration, native attack/swing entrypoints, target/session revalidation and a dedicated module-control IPC operation (the existing memory opcode remains read-only). None is derived by reusing Melody's Windows signatures. The engine input/version is unchanged; no unrelated bootstrap repeat is needed.

The remaining Fly input is the **packet event/session delivery** implementation: `events/PacketEvent`, `PacketEventBus`, the session exposing `clientBound`, and the Gradle codec dependency/version declarations. `EntityTracker` has now been supplied; collision/governor helpers are no longer requested or required. A real version-compatible transport/codec still has to deliver decoded inputs and inject client-bound motion even when Fly is the only feature allowed custom packet behavior. The host has no such transport yet. No account credentials, private worlds, giant dumps or replacement Minecraft APK are requested.

## Validation

`python tools/test_client_base.py`: **30 tests pass**, including C++17 `-Wall -Wextra -Werror` compilation/execution of policy and tracker regression executables. Tests retain KillAura selection/cadence coverage and Fly math/direction/enable/session/correction coverage, now requiring direct output without any collision/governor callback. Tracker tests cover startup gating, runtime-ID/direction validation, outgoing self updates, server corrections, raw coordinate retention, invalid inputs, respawn/dimension generations and disconnect/reconnect reset. Local ASan/UBSan runs of both executables also passed. Android/gameplay/proxy integration is not established by these tests. Existing engine archive, app/native backend, memory IPC and overlay remain unchanged. [Baseline module-source CI 40004765471](https://github.com/Alextheplayer919/Zips-ig/actions/runs/40004765471) passed for d1684d5 before this refinement; current-change Android CI is pending at this update. No new module APK is delivered.
