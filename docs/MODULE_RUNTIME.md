# 0.6.4 internal live module engine — MotionFly & KillAura

Following the user's choice of **in-process internal handling** inside the hosted game (covering both local single-player worlds and remote multiplayer servers without an external proxy), `0.6.4` (`versionCode 17`) implements the concrete ARM64 `1.26.50.4` bindings, thread-safe coordinator, dedicated module IPC, and backend-acknowledged overlay toggles.

## Architecture

1. **Fingerprint-gated RELRO vtable interception (`slot_patch.h`, `native_engine_abi.h`, `native_module_engine.cpp`)**
   - Before installing any hook in `:game_host`, the engine verifies via `/proc/self/mem` (`pread`):
     - Loaded `libminecraftpe.so` ELF64 `EM_AARCH64` GNU Build ID `afa62bd3385161f1facfe36aad58ac63998f46d2`.
     - Exact RTTI `offset_to_top == 0` and `typeinfo` pointers on all 7 required vtables (`LoopbackPacketSender`, `LegacyClientNetworkHandler`, `GameMode`, `SurvivalMode`, `LocalPlayer`, `PlayerAuthInputPacket`, `SetActorMotionPacket`).
     - Exact vtable slot target offsets and first two 32-bit ARM64 instructions (`uint32_t[2]`) on all 17 virtual methods and 7 direct helper functions.
   - Only 8-byte aligned `.data.rel.ro` pointer slots (14 slots) are atomically swapped (`SlotPatch`), restoring `PROT_READ` after each swap and rolling back all slots if any single swap fails. Executable `.text` instructions are never modified.
   - On non-ARM64 environments or when Minecraft `1.26.50.4` is not loaded, hook installation fails closed and modules remain `OFF`.

2. **Internal packet path — scoped strictly to MotionFly**
   - Outbound `LoopbackPacketSender::sendToServer(Packet&)` (`slot 5`) checks `mSenderType == 0` (client sender, ignoring any integrated-server instance) and exact `PlayerAuthInputPacket` vtable identity (`0x12e86ab0`).
   - Extracts `mRot` (`+0x30`), `mPos` (`+0x38`), `mMove` (`+0x64`), and `mInputData` (`+0x88`: `Jumping=6`, `Sneaking=8`, `WantUp=16`, `WantDown=17`), forwards the **untouched original `PlayerAuthInputPacket` first**, and routes the decoded event into `InternalModuleCoordinator` -> `FlyPacketRuntime`.
   - Inbound `LegacyClientNetworkHandler` handlers (`StartGame`, `MovePlayer`, `ChangeDimension`, `Respawn`, `Disconnect`, and destructors) feed session lifecycle and self-tracking events; mid-world enablement also seeds self state from the live `LocalPlayer` on the client game thread so MotionFly does not require rejoining a world.
   - When `MotionFlyPolicy` emits `EmitClientMotion`, `Arm64EngineBackend` constructs a stack `SetActorMotionPacket` via the engine's own `0xd14042c` constructor (`mRuntimeId` `+0x30`, `mMotion` `+0x38`, `mTick = 0` `+0x48`) and dispatches it through `LegacyClientNetworkHandler::handle(SetActorMotionPacket)` (`0xad76d18`, resolved live via `IClientInstance -> Minecraft -> GameSession` `NonOwnerPointer<NetEventCallback>` with `LocalPlayer::lerpMotion` fallback). `CollisionGuard` and `MovementCompliance` remain removed.

3. **Native combat path — KillAura (zero custom packet/proxy handling)**
   - `GameMode::tick` and `SurvivalMode::tick` (`slot 10`) check that `[gm + 0x8]` has exact `LocalPlayer` vtable identity (`0x12d08918`), ignoring integrated-server `ServerPlayer` ticks in local worlds.
   - `Arm64EngineBackend` enumerates actors via `ILevel::getRuntimeActorList()` (`vtable +0xa30`, freeing the returned buffer via the engine's `_ZdlPv` PLT), filters `ActorCategory` (`Player=1`, `Mob=2`) and alive state (`vtable +0x178`), pre-filters by range before raycasting `Actor::canSee` (`0xf5841c0`, `ShapeType::Collision=1`), and reads `Actor::getHurtTime` (`0xf590ef4`).
   - Selected targets are re-resolved by `ActorRuntimeID` via `ILevel::getRuntimeEntity` (`vtable +0x208`) immediately before calling `GameMode::attack(Actor&, Vec3 const&)` (`vtable +0x80`) and `LocalPlayer::swing(ActorSwingSource::Attack=4, false)` (`vtable +0x370`).

4. **UI & IPC separation (`ModuleProtocol`, `ModuleClient`, `ModuleState`, `OverlayWindow`, `ControlsActivity`)**
   - Dedicated `ModuleProtocol` opcodes (`6..8`) keep `MemoryProtocol` opcode `2` strictly read-only and never auto-launch or prepare Minecraft.
   - `OverlayWindow` exposes interactive **MotionFly** (`Movement`) and **KillAura** (`Combat`, with `Mobs` / `Multi` / `Hurt` option chips) buttons whose state (`OFF`, `ARMED`, `ON`) comes strictly from `:game_host` replies—never optimistic local flips. `Visual` and `Misc` remain empty; diagnostic counters and `Disarm native hooks` stay in `ControlsActivity`.

## Validation & scope

- **34 local source/native tests pass**, including `-Wall -Wextra -Werror` and ASan/UBSan execution of `module_policies_test`, `self_tracker_test`, `module_runtime_test`, `slot_patch_test`, and `native_module_engine_test`, plus byte-for-byte static verification of all 7 vtables, 17 virtual methods, and 7 helper functions against the hash-verified `libminecraftpe.so`.
- Android 13 x86_64 CI validates compilation of both `arm64-v8a` and `x86_64` NDK targets, UI/overlay controls, classloader isolation, and closed-fail IPC rejection when no ARM64 `1.26.50.4` session is active. Actual ARM64 Minecraft gameplay execution requires testing on the target Android 13 phone.
