# 0.6.6 internal live module engine — MotionFly & KillAura

Following the user's choice of **in-process internal handling** inside the hosted game (covering both local single-player worlds and remote multiplayer servers without an external proxy), `0.6.4`–`0.6.6` (`versionCode 19`) implement concrete ARM64 `1.26.50.4` bindings, a thread-safe coordinator, dedicated module IPC, and backend-acknowledged overlay toggles.

## Root causes resolved in 0.6.6 (after 0.6.5 phone multiplayer test)

On `0.6.5`, Xbox sign-in (`XAL`) and joining a multiplayer anarchy server succeeded on phone, while neither **MotionFly** nor **KillAura** actuated in-game. Static caller/disassembly analysis of the hash-verified `libminecraftpe.so` (`1.26.50.4`) confirmed full in-process `:game_host` memory ownership (`same PID/UID`, RW `/proc/self/mem`, writable `.data.rel.ro` via `mprotect`) and pinpointed the engine-level causes:

1. **`GameMode::tick` (`vt+0x50`) is never called in Bedrock `1.26.50.4` (broke BOTH MotionFly & KillAura):**
   - Full-binary `.text` + `.rela.dyn` call-site scan across all 64 `Player::getGameMode()` (`0xf9fecac`) callers and all 508 `[reg, #0x9e8]` loads proved `GameMode::tick` (`0xf8a33ec`) and `SurvivalMode::tick` (`0xf8a3dcc`) have **0 direct and 0 virtual callers** in `1.26.50.4`. As a result, `onGameThreadTick` never ran (`in_world=0`, `aura_ticks=0`, `fly_active=0`, `aura_active=0`).
   - **0.6.6 fix:** Hooks `LocalPlayer::normalTick` (`11LocalPlayer` `0x12d08918` **slot `[25]`**, `vt+0xc8 = 0xaac7cf8`, dispatched every client tick via `0xf56e0e8` / `0xf9fb290`) and `~LocalPlayer` D1/D0 at **slots `[8]` / `[9]`** (`0xaac61a0` / `0xaac658c`, after `Actor`'s 8 pre-destructor virtuals), resolving `GameMode*` dynamically from `[LocalPlayer + 0x9e8]` (`Player::mGameMode`).
2. **`ReplayStateComponent` (`server-auth-with-rewind`) dropped synthetic `SetActorMotionPacket` (`mTick = 0`):**
   - In multiplayer (`lcnh != nullptr`), `0.6.4`/`0.6.5` delivered motion exclusively through `LegacyClientNetworkHandler::handle(SetActorMotionPacket)` (`0xad76d18`). On modern servers advertising `server-auth-with-rewind` (`[ReplayStateComponent + 0x60] == 2`), `0xba6c394` -> `0xb83c248` returns `1`, queueing synthetic `mTick = 0` packets into `ReplayStateComponent` (`0xb83ba58`) and **skipping `LocalPlayer::lerpMotion` (`vt+0xb8 = 0xf578d1c`)**.
   - **0.6.6 fix:** After forwarding the untouched outbound `PlayerAuthInputPacket` via `LoopbackPacketSender::sendToServer`, `deliverClientMotion` invokes `LocalPlayer::lerpMotion(lp, &motion)` directly (`[lp + 0x208] + 0x18..0x20` `StateVectorComponent::mPosDelta`), bypassing `ReplayStateComponent` drops cleanly.
3. **Component offset & width fixes (`LoopbackPacketSender`, `StateVectorComponent`, `AABBShapeComponent`, `MobHurtTimeComponent`):**
   - `LoopbackPacketSender` (`0x12e2f288`) is the sole concrete `PacketSender` subclass in `1.26.50.4`; `[sender + 0x28]` is read as a **32-bit `uint32_t`** (`0U` = `ClientNetworkSystem*`, matching `ldr w8, [x0, #0x28]` at `0xc68fa50` / `0xc68fd40`) so uninitialized padding at `+0x2c..0x2f` is never misread.
   - `Actor::getPosition` uses `0xf563e6c` (`StateVectorComponent::mPos` at `[actor + 0x208] + 0x00`, matching `PlayerAuthInputPacket::mPos` rather than `+0x0c` `mPosPrev`); `[actor + 0x210]` (`AABBShapeComponent*`) computes exact hitbox-center `hitPos` (`chest` height on `RemotePlayer` rather than above the `1.8m` box); `[actor + 0x218]` reads `ActorRotationComponent*`; `[actor + 0x408]` (`MobHurtTimeComponent*`) reads live combat `HurtTime` (`10 -> 0`); and `tryReadActorRuntimeId` reads `RuntimeIDComponent` (`0xfc0dbbb5`) without calling `abort()`.

## Architecture

1. **Fingerprint-gated RELRO vtable interception (`slot_patch.h`, `native_engine_abi.h`, `native_module_engine.cpp`)**
   - Before installing any hook in `:game_host`, the engine verifies via `/proc/self/mem` (`pread`):
     - Loaded `libminecraftpe.so` ELF64 `EM_AARCH64` GNU Build ID `afa62bd3385161f1facfe36aad58ac63998f46d2`.
     - Exact RTTI type names on all 7 required vtables (`LoopbackPacketSender`, `LegacyClientNetworkHandler`, `GameMode`, `SurvivalMode`, `LocalPlayer`, `RemotePlayer`, `PlayerAuthInputPacket`).
     - Exact vtable slot target offsets on all 16 verified virtual slots and first two 32-bit ARM64 instructions (`uint32_t[2]`) on all 7 direct helper functions (`30/30` binary checks).
   - Only 8-byte aligned `.data.rel.ro` pointer slots (**11 slots**: 1 `LoopbackPacketSender::sendToServer`, 7 `LegacyClientNetworkHandler` session/disconnect/destructor slots, 3 `LocalPlayer` `[8, 9, 25]` slots) are atomically swapped (`SlotPatch`), restoring `PROT_READ` after each swap and rolling back all slots if any single swap fails. Executable `.text` instructions are never modified.

2. **Internal packet path — scoped strictly to MotionFly**
   - Outbound `LoopbackPacketSender::sendToServer(Packet&)` (`slot 5`, reached from `LoopbackPacketSender::send` `0xc68fa2c` via `0xc6a4fc0` whenever `uint32_t [sender + 0x28] == 0U`) verifies `PlayerAuthInputPacket` (`0x12e86ab0`), forwards the **untouched original `PlayerAuthInputPacket` first**, and routes the decoded event into `InternalModuleCoordinator` -> `FlyPacketRuntime`.
   - Inbound `LegacyClientNetworkHandler` handlers (`StartGame`, `MovePlayer`, `ChangeDimension`, `Respawn`, `Disconnect`, and destructors) maintain session lifecycle and self-tracking; mid-world enablement also seeds `SelfTracker` from the live `LocalPlayer` on the next `LocalPlayer::normalTick`.
   - When `MotionFlyPolicy` emits `EmitClientMotion`, `BedrockEngineBackend::deliverClientMotion` applies `LocalPlayer::lerpMotion` (`vt+0xb8 = 0xf578d1c`) directly on the verified `LocalPlayer`. `CollisionGuard` and `MovementCompliance` remain removed.

3. **Native combat path — KillAura (zero custom packet/proxy handling)**
   - `LocalPlayer::normalTick` (`slot 25`, `0xaac7cf8`) verifies `11LocalPlayer` vtable (`0x12d08918`) and `Actor::isClientSide` (`0xf56aa70`), ignoring integrated-server `ServerPlayer` instances.
   - `BedrockEngineBackend` enumerates actors via `ILevel::getRuntimeActorList()` (`vtable +0xa30`, freeing the returned buffer via the engine's `_ZdlPv` PLT `0x12911250`), filters `ActorCategory` (`Player=1`, `Mob=2`) and alive state (`vtable +0x178`), pre-filters by range before raycasting `Actor::canSee` (`0xf5841c0`, `ShapeType::Collision=1`), and reads `MobHurtTimeComponent` (`[actor + 0x408]`).
   - Selected targets are re-resolved by `ActorRuntimeID` via `ILevel::getRuntimeEntity` (`vtable +0x208`) immediately before computing AABB hitbox center (`[target + 0x210]`) and calling `GameMode::attack(Actor&, Vec3 const&)` (`vtable +0x80` on live `[lp + 0x9e8]`) + `LocalPlayer::swing(ActorSwingSource::Attack=4, false)` (`vtable +0x370`).

4. **UI & IPC separation (`ModuleProtocol`, `ModuleClient`, `ModuleState`, `OverlayWindow`, `ControlsActivity`)**
   - Dedicated `ModuleProtocol` opcodes (`6..8`) keep `MemoryProtocol` opcode `2` strictly read-only and never auto-launch or prepare Minecraft.
   - `OverlayWindow` exposes interactive **MotionFly** (`Movement`) and **KillAura** (`Combat`, with `Mobs` / `Multi` / `Hurt` option chips) buttons whose state (`OFF`, `ARMED`, `ON`) comes strictly from `:game_host` replies—never optimistic local flips. `Visual` and `Misc` remain empty; diagnostic counters (`Client normalTicks observed`, `AuthInput`, `MotionSet`, `Attacks`) and `Disarm native hooks` stay in `ControlsActivity`.
