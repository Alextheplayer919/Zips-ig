# Selected modules: native KillAura + supplied packet-based MotionFly

## Scope confirmed by the user

Use the KillAura from the newly supplied `Melody-1.20.51.zip`, adapt it to **Minecraft Bedrock Android26.50**. Keep **MotionFly's supplied packet-driven behavior**, with custom packet handling **only for Fly**. Do not turn KillAura into a proxy module or copy Windows bindings into Android. Existing game rendering/lifecycle, startup pins and working overlay remain untouched.

Fetched the exact Melody blob from repository main via GitHub API; this did not switch branches or restore main's legacy tree. Archive and extracted review files remain ignored under `.cache/module-inputs/`. Input hashes/provenance are in [MODULE_INPUTS.json](MODULE_INPUTS.json). The archive is a Windows C++ project: its memory helper calls `GetModuleHandleA("Minecraft.Windows.exe")` and uses MinHook/Windows signatures. Those bindings, layouts, packet structs and vtable indices are not Android26.50 SDK evidence. Melody's MIT attribution/license is retained in `third_party/melody/LICENSE`.

## Implemented source ports — not live modules yet

`host/src/main/cpp/modules/module_policies.h` is portable C++ logic, exercised by `tests/native/module_policies_test.cpp` through the existing source/native test runner.

**KillAura:** adapted Melody's nearest-target ordering, visible/wall range, single/multi selection, mob option, hurt-time filter and tick cadence. Corrected the source's assignment-in-condition mode bug (`Mode = 1` / `Mode = 0`), added invalid-state/finite-value checks, friend/self exclusion, duplicate suppression and world/session reset. Inputs are typed entity snapshots; outputs are session-scoped native attack intents, not raw pointers or packets. The native adapter must obtain real attackability/visibility/player state and re-resolve/revalidate targets on the game thread before using the actual engine attack/swing functions.

**Not carried over from Melody:** direct packet rotations, manual Interact/equipment packet sends, old Windows SDK addresses/signatures, silent equipment switching and ImGui range/target renderer. In particular, its custom packet features conflict with the Fly-only packet scope. Ordinary Minecraft native attacks may naturally produce normal game traffic; “not proxy-based” does not mean the game sends no packets.

**MotionFly:** ported the provided speeds/defaults, input aliases, per-20 scaling, horizontal cap, yaw rotation/normalization, ascent/descent factors, glide and 4.5-block displacement rebase. It accepts only the semantic **client-to-server PlayerAuthInput** event while enabled. Its output is an intent for **client-bound SetEntityMotion**, matching the attachment's `session.clientBound(motionPacket)`; it does not rewrite outgoing position packets or implement a lagback/anti-cheat bypass. Other packet kinds/directions and disabled state produce no intent. Session/entity changes disable it.

Collision and adaptive movement checks are explicit required callbacks, **not dummy implementations**. Missing collision data fails unavailable. Adaptive mode requires a real governor that implements the attachment's governed horizontal speed, settle-vertical rule and governed vertical speed. The collision adapter must use real `CollisionGuard.feetY()`/world data, not assume the tracker position is a feet position. The policies themselves read no game memory, send no packets and touch no GUI/JNI.

## What is still unimplemented

These are **logic ports, not completed working modules**. They are not connected to native Minecraft, compiled into its engine, exposed via JNI/IPC, installed as packet hooks, or shown as usable toggles. No new module APK is being delivered. The last validated application remains 0.6.2; categories stay empty rather than pretending a backend exists.

KillAura still needs verified Android26.50 local-player/world/entity access, tick integration, native attack/swing entrypoints, target/session revalidation and a dedicated module-control IPC operation (the existing memory opcode remains read-only). None is derived by reusing Melody's Windows signatures. The engine input/version is unchanged; no unrelated bootstrap repeat is needed.

To preserve MotionFly **as provided**, the concrete missing source is the implementation behind its imports: `core/proxy/EntityTracker`, `CollisionGuard`, `MovementCompliance`, `events/PacketEvent`, `PacketEventBus`, and the session implementation exposing `clientBound`, together with the Gradle dependency/version declarations for the Cloudburst codecs. The attachment references these but does not define them. A zip of those specific source folders/build files is useful; account credentials, private worlds, giant dumps or another original Minecraft APK are not needed. A full proxy requires a real version-compatible connection/codec/session path even when Fly is the only feature allowed to alter/inject packet behavior. The host currently has no such proxy transport. These gaps are not silently replaced with pass-through collision or made-up network methods.

## Validation

`python tools/test_client_base.py`: **29 tests pass**, including compiling and executing the policy regression executable with C++17, `-Wall -Wextra -Werror`. Tests cover single/multi correctness, ordering/ranges, delay, mobs/hurt/friend filtering, duplicate/invalid data and session changes; Fly's enable/disable and packet-direction/kind scope, numerical movement, speed cap, correction rebase, required dependency failures and adaptive output. Identity collision/governor fixtures in tests are not production implementations. Android/gameplay/proxy integration is not established by these tests. A separate AddressSanitizer/UndefinedBehaviorSanitizer run of the policy executable also passed locally. Existing engine archive, app/native backend and memory IPC source are unchanged.
