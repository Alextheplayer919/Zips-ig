#include "loaded_identity.h"
#include "modules/internal_module_coordinator.h"
#include "modules/native_engine_abi.h"
#include "modules/slot_patch.h"
#include <jni.h>

#if defined(__aarch64__)
#include <elf.h>
#include <fcntl.h>
#include <unistd.h>
#endif

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace {

using namespace clientbase::modules;

#if defined(__aarch64__)

std::mutex gLifecycleMutex;
std::recursive_mutex gSessionAccessMutex;
std::atomic<bool> gHooksInstalled{false};
std::atomic<std::uintptr_t> gLoadBias{0};
std::atomic<void*> gActiveLcnh{nullptr};
std::atomic<void*> gActiveLocalPlayer{nullptr};
std::atomic<bool> gLocalPlayerBound{false};
std::atomic<bool> gGameModeBound{false};
std::atomic<std::uint64_t> gClientNormalTicks{0};
std::string gStatusMessage{"Module engine idle · toggle a module while Bedrock is running"};

constexpr std::size_t kMaxPatchedSlots = 16;
SlotPatch gSlotPatches[kMaxPatchedSlots];
std::size_t gSlotPatchCount = 0;

// Saved original virtual slot targets
using SendToServerFn = void (*)(void* self, void* packet);
using LcnhDtorFn = void (*)(void* self);
using LcnhHandlePacketFn = void (*)(void* self, const void* netId, const void* packet);
using LocalPlayerTickFn = void (*)(void* self);
using LocalPlayerDtorFn = void (*)(void* self);

SendToServerFn origSendToServer = nullptr;
LcnhDtorFn origLcnhDtorD1 = nullptr;
LcnhDtorFn origLcnhDtorD0 = nullptr;
LcnhHandlePacketFn origLcnhDisconnect = nullptr;
LcnhHandlePacketFn origLcnhStartGame = nullptr;
LcnhHandlePacketFn origLcnhMovePlayer = nullptr;
LcnhHandlePacketFn origLcnhRespawn = nullptr;
LcnhHandlePacketFn origLcnhChangeDim = nullptr;
LocalPlayerDtorFn origLocalPlayerDtorD1 = nullptr;
LocalPlayerDtorFn origLocalPlayerDtorD0 = nullptr;
LocalPlayerTickFn origLocalPlayerNormalTick = nullptr;

template <typename Fn>
Fn resolveFn(std::uintptr_t elfVa) noexcept {
    const auto bias = gLoadBias.load(std::memory_order_acquire);
    return bias ? reinterpret_cast<Fn>(bias + elfVa) : nullptr;
}

template <typename Fn>
Fn callVirtual(const void* obj, std::size_t byteOffset) noexcept {
    const auto vtbl = abi::readField<std::uintptr_t>(obj, 0);
    const auto fnAddr = abi::readField<std::uintptr_t>(reinterpret_cast<const void*>(vtbl), byteOffset);
    return reinterpret_cast<Fn>(fnAddr);
}

bool preadExact(int fd, std::uintptr_t addr, void* out, std::size_t size) noexcept {
    return ::pread(fd, out, size, static_cast<off_t>(addr)) == static_cast<ssize_t>(size);
}

bool verifyVtable(int memFd, std::uintptr_t bias, const abi::VtableSpec& spec, std::string* err) {
    std::uintptr_t rttiPtr = 0;
    if (!preadExact(memFd, bias + spec.addressPointVa - sizeof(std::uintptr_t), &rttiPtr, sizeof(rttiPtr)) || !rttiPtr) {
        if (err) *err = "Unreadable RTTI pointer for " + std::string(spec.rttiTypeName);
        return false;
    }
    std::uintptr_t namePtr = 0;
    if (!preadExact(memFd, rttiPtr + sizeof(std::uintptr_t), &namePtr, sizeof(namePtr)) || !namePtr) {
        if (err) *err = "Unreadable RTTI type name pointer for " + std::string(spec.rttiTypeName);
        return false;
    }
    char buf[96]{};
    const std::size_t want = spec.rttiTypeName.size() + 1;
    if (want >= sizeof(buf) || !preadExact(memFd, namePtr, buf, want)) {
        if (err) *err = "Unreadable RTTI string for " + std::string(spec.rttiTypeName);
        return false;
    }
    if (std::string_view(buf, spec.rttiTypeName.size()) != spec.rttiTypeName || buf[spec.rttiTypeName.size()] != '\0') {
        if (err) *err = "RTTI mismatch for " + std::string(spec.rttiTypeName);
        return false;
    }
    return true;
}

bool verifyMethod(int memFd, std::uintptr_t bias, const abi::MethodSpec& spec, std::string* err) {
    std::uintptr_t actual = 0;
    const std::uintptr_t slotAddr = bias + spec.vtableAddressPointVa + spec.slotIndex * sizeof(std::uintptr_t);
    if (!preadExact(memFd, slotAddr, &actual, sizeof(actual))) {
        if (err) *err = "Unreadable vtable slot";
        return false;
    }
    if (actual != bias + spec.expectedFnVa) {
        char msg[160]{};
        std::snprintf(msg, sizeof(msg), "Vtable slot mismatch at +0x%zx (got 0x%zx, expected 0x%zx)",
                      static_cast<std::size_t>(spec.vtableAddressPointVa + spec.slotIndex * 8),
                      static_cast<std::size_t>(actual - bias),
                      static_cast<std::size_t>(spec.expectedFnVa));
        if (err) *err = msg;
        return false;
    }
    return true;
}

bool verifyFunction(int memFd, std::uintptr_t bias, const abi::DirectFnSpec& spec, std::string* err) {
    std::uint32_t insns[2]{};
    if (!preadExact(memFd, bias + spec.fnVa, insns, sizeof(insns))) {
        if (err) *err = "Unreadable helper prologue";
        return false;
    }
    if (insns[0] != spec.expectedInsn0 || insns[1] != spec.expectedInsn1) {
        char msg[160]{};
        std::snprintf(msg, sizeof(msg), "Helper prologue mismatch at 0x%zx (0x%08x 0x%08x)",
                      static_cast<std::size_t>(spec.fnVa), insns[0], insns[1]);
        if (err) *err = msg;
        return false;
    }
    return true;
}

bool isVerifiedLocalPlayer(const void* lp, std::uintptr_t bias) noexcept {
    if (lp == nullptr || bias == 0) return false;
    return abi::readField<std::uintptr_t>(lp, 0) == bias + abi::kLocalPlayerVtable.addressPointVa;
}

void* resolveVerifiedGameMode(const void* lp, std::uintptr_t bias) noexcept {
    if (!isVerifiedLocalPlayer(lp, bias)) return nullptr;
    void* gm = abi::readField<void*>(lp, abi::kPlayerGameModeOffset);
    if (gm == nullptr) return nullptr;
    const auto gmVtbl = abi::readField<std::uintptr_t>(gm, 0);
    if (gmVtbl != bias + abi::kGameModeVtable.addressPointVa
            && gmVtbl != bias + abi::kSurvivalModeVtable.addressPointVa) {
        return nullptr;
    }
    return gm;
}

struct RawActorVector {
    void** begin;
    void** end;
    void** cap;
};

class BedrockEngineBackend final : public InternalEngineBackend {
public:
    std::string_view engineBuildId() const noexcept override {
        return supportedEngineBuild;
    }

    bool canDeliverClientMotion() const noexcept override {
        const auto bias = gLoadBias.load(std::memory_order_acquire);
        void* lp = gActiveLocalPlayer.load(std::memory_order_acquire);
        return isVerifiedLocalPlayer(lp, bias)
            && abi::readField<const void*>(lp, abi::kActorStateVectorOffset) != nullptr;
    }

    bool deliverClientMotion(std::uint64_t runtimeId, const Vec3& motion) override {
        if (runtimeId == 0 || !motion.finite()) return false;
        const auto bias = gLoadBias.load(std::memory_order_acquire);
        void* lp = gActiveLocalPlayer.load(std::memory_order_acquire);
        if (!isVerifiedLocalPlayer(lp, bias)) return false;
        if (abi::readField<const void*>(lp, abi::kActorStateVectorOffset) == nullptr) return false;
        if (abi::tryReadActorRuntimeId(lp) != runtimeId) return false;

        // Apply directly to LocalPlayer::lerpMotion (vt+0xb8 = 0xf578d1c -> StateVectorComponent::mPosDelta).
        // Under server-auth-with-rewind ([ReplayStateComponent+0x60]==2), synthetic SetActorMotionPackets
        // with mTick=0 passed through LCNH::handle(SetActorMotionPacket) are queued into ReplayStateComponent
        // and dropped instead of calling LocalPlayer::lerpMotion.
        using LerpMotionFn = void (*)(void*, const Vec3*);
        callVirtual<LerpMotionFn>(lp, abi::kActorLerpMotionVtableByteOffset)(lp, &motion);
        return true;
    }

    std::optional<LiveSelfState> readLocalPlayer() override {
        const auto bias = gLoadBias.load(std::memory_order_acquire);
        void* lp = gActiveLocalPlayer.load(std::memory_order_acquire);
        if (!isVerifiedLocalPlayer(lp, bias)) return std::nullopt;
        if (!abi::readField<const void*>(lp, abi::kActorEntityContextMapOffset)
                || !abi::readField<const void*>(lp, abi::kActorLevelOffset)
                || !abi::readField<const void*>(lp, abi::kActorStateVectorOffset)) {
            return std::nullopt;
        }

        using GetUniqueIdFn = const std::int64_t* (*)(const void*);
        using GetPosFn = const Vec3* (*)(const void*);
        using GetDimIdFn = std::int32_t (*)(const void*);
        using IsAliveFn = bool (*)(const void*);

        auto getUniqueId = resolveFn<GetUniqueIdFn>(abi::kActorGetUniqueId.fnVa);
        auto getPos = resolveFn<GetPosFn>(abi::kActorGetPosition.fnVa);
        auto getDimId = resolveFn<GetDimIdFn>(abi::kPlayerGetDimensionId.fnVa);
        if (!getUniqueId || !getPos || !getDimId) return std::nullopt;

        LiveSelfState state{};
        state.runtimeId = abi::tryReadActorRuntimeId(lp);
        if (state.runtimeId == 0) return std::nullopt;
        const std::int64_t* uidPtr = getUniqueId(lp);
        state.uniqueId = uidPtr ? *uidPtr : -1;
        const Vec3* posPtr = getPos(lp);
        if (!posPtr || !posPtr->finite()) return std::nullopt;
        state.position = *posPtr;

        const void* rotComp = abi::readField<const void*>(lp, abi::kActorRotationOffset);
        if (rotComp != nullptr) {
            state.pitch = abi::readField<float>(rotComp, 0);
            state.yaw = abi::readField<float>(rotComp, sizeof(float));
        }

        state.dimension = static_cast<int>(getDimId(lp));
        state.alive = callVirtual<IsAliveFn>(lp, abi::kActorIsAliveVtableByteOffset)(lp);
        return state;
    }

    std::vector<Target> collectTargets(const LiveSelfState& self, float maxRange) override {
        std::vector<Target> out;
        const auto bias = gLoadBias.load(std::memory_order_acquire);
        void* lp = gActiveLocalPlayer.load(std::memory_order_acquire);
        if (!isVerifiedLocalPlayer(lp, bias)) return out;
        void* level = abi::readField<void*>(lp, abi::kActorLevelOffset);
        if (level == nullptr || abi::readField<std::uintptr_t>(level, 0) == 0) return out;

        using GetRuntimeActorListFn = RawActorVector (*)(void* level);
        using OperatorDeleteFn = void (*)(void*);
        auto getActors = callVirtual<GetRuntimeActorListFn>(
            level, abi::kLevelGetRuntimeActorListVtableByteOffset);
        auto engineDelete = resolveFn<OperatorDeleteFn>(abi::kEngineOperatorDelete);
        if (!getActors || !engineDelete) return out;

        const RawActorVector raw = getActors(level);
        if (!raw.begin || raw.end < raw.begin) return out;

        const std::size_t count = static_cast<std::size_t>(raw.end - raw.begin);
        const std::size_t capped = count > 512 ? 512 : count;
        std::vector<void*> actors(raw.begin, raw.begin + capped);
        engineDelete(raw.begin);

        using GetPosFn = const Vec3* (*)(const void*);
        using CanSeeFn = bool (*)(const void*, const void*, int);
        using IsAliveFn = bool (*)(const void*);

        auto getPos = resolveFn<GetPosFn>(abi::kActorGetPosition.fnVa);
        auto canSee = resolveFn<CanSeeFn>(abi::kActorCanSee.fnVa);
        if (!getPos || !canSee) return out;

        out.reserve(actors.size());
        for (void* actor : actors) {
            if (actor == nullptr || actor == lp) continue;
            if (!abi::readField<const void*>(actor, abi::kActorEntityContextMapOffset)
                    || !abi::readField<const void*>(actor, abi::kActorStateVectorOffset)
                    || !abi::readField<const void*>(actor, abi::kActorLevelOffset)) {
                continue;
            }

            const auto categories = abi::readField<std::uint32_t>(actor, abi::kActorCategoriesOffset);
            const auto actorVtbl = abi::readField<std::uintptr_t>(actor, 0);
            const bool isPlayer = (categories & abi::kActorCategoryPlayer) != 0U
                || actorVtbl == bias + abi::kRemotePlayerVtable.addressPointVa;
            const bool isMob = !isPlayer && (categories & abi::kActorCategoryMob) != 0U;
            if (!isPlayer && !isMob) continue;

            const Vec3* pos = getPos(actor);
            if (!pos || !pos->finite()) continue;
            if (distance(self.position, *pos) > static_cast<double>(maxRange)) continue;

            const std::uint64_t rid = abi::tryReadActorRuntimeId(actor);
            if (rid == 0 || rid == self.runtimeId) continue;

            const bool alive = callVirtual<IsAliveFn>(actor, abi::kActorIsAliveVtableByteOffset)(actor);
            if (!alive) continue;

            // Actor::canSee (0xf5841c0) safely resolves WeakRef<Dimension> from EntityContext (lp+8)
            // via 0xf9b9c00 and returns false if unavailable.
            const bool visible = canSee(lp, actor, 1);
            const int hurt = abi::readActorHurtTicks(actor);

            Target t{};
            t.id = rid;
            t.position = *pos;
            t.player = isPlayer;
            t.alive = true;
            t.friendEntity = false;
            t.attackable = true;
            t.visible = visible;
            t.hurtTicks = hurt;
            out.push_back(t);
        }
        return out;
    }

    bool attackTarget(std::uint64_t expectedSelfRuntimeId, std::uint64_t targetRuntimeId) override {
        const auto bias = gLoadBias.load(std::memory_order_acquire);
        void* lp = gActiveLocalPlayer.load(std::memory_order_acquire);
        void* gm = resolveVerifiedGameMode(lp, bias);
        if (lp == nullptr || gm == nullptr) return false;

        using GetPosFn = const Vec3* (*)(const void*);
        using IsAliveFn = bool (*)(const void*);
        using GetRuntimeEntityFn = void* (*)(void* level, std::uint64_t runtimeId, bool includeRemoved);
        using AttackFn = bool (*)(void* gameMode, void* targetActor, const Vec3* hitPos);

        auto getPos = resolveFn<GetPosFn>(abi::kActorGetPosition.fnVa);
        if (!getPos) return false;
        if (abi::tryReadActorRuntimeId(lp) != expectedSelfRuntimeId) return false;
        if (!callVirtual<IsAliveFn>(lp, abi::kActorIsAliveVtableByteOffset)(lp)) return false;

        void* level = abi::readField<void*>(lp, abi::kActorLevelOffset);
        if (level == nullptr || abi::readField<std::uintptr_t>(level, 0) == 0) return false;

        void* target = callVirtual<GetRuntimeEntityFn>(
            level, abi::kLevelGetRuntimeEntityVtableByteOffset)(level, targetRuntimeId, false);
        if (target == nullptr || target == lp) return false;
        if (!callVirtual<IsAliveFn>(target, abi::kActorIsAliveVtableByteOffset)(target)) return false;

        const Vec3* rawPos = getPos(target);
        const Vec3 fallbackPos = (rawPos && rawPos->finite()) ? *rawPos : Vec3{};
        const Vec3 hitPos = abi::computeTargetHitPos(target, fallbackPos);
        if (!hitPos.finite()) return false;

        callVirtual<AttackFn>(gm, abi::kGameModeAttackVtableByteOffset)(gm, target, &hitPos);
        return true;
    }

    void swingArm(std::uint64_t expectedSelfRuntimeId) override {
        const auto bias = gLoadBias.load(std::memory_order_acquire);
        void* lp = gActiveLocalPlayer.load(std::memory_order_acquire);
        if (!isVerifiedLocalPlayer(lp, bias)) return;
        if (abi::tryReadActorRuntimeId(lp) != expectedSelfRuntimeId) return;

        using SwingFn = void (*)(void* localPlayer, int swingSource, bool unknownFlag);
        callVirtual<SwingFn>(lp, abi::kLocalPlayerSwingVtableByteOffset)(
            lp, abi::kActorSwingSourceAttack, false);
    }
};

BedrockEngineBackend gEngineBackend;
InternalModuleCoordinator gCoordinator;

// --- Vtable hook trampolines ---
void hookSendToServer(void* self, void* packet) {
    const auto bias = gLoadBias.load(std::memory_order_acquire);
    bool isClientAuthInput = false;
    PacketEvent authEv{};

    if (self != nullptr && packet != nullptr && bias != 0) {
        // Must read 32-bit uint32_t __index at +0x28 (NOT 64-bit size_t across +0x2c..0x2f tail padding).
        const auto variantIdx = abi::readField<std::uint32_t>(self, abi::kSenderVariantIndexOffset);
        if (variantIdx == 0U) {
            const auto pktVtbl = abi::readField<std::uintptr_t>(packet, 0);
            if (pktVtbl == bias + abi::kPlayerAuthInputVtable.addressPointVa) {
                authEv = abi::decodeAuthInputPacket(packet);
                isClientAuthInput = true;
            }
        }
    }

    // Forward original outbound PlayerAuthInputPacket untouched (exact MotionFly semantics).
    if (origSendToServer) {
        origSendToServer(self, packet);
    }

    if (isClientAuthInput) {
        std::lock_guard<std::recursive_mutex> sessionLock(gSessionAccessMutex);
        gCoordinator.onOutboundAuthInput(gEngineBackend, authEv);
    }
}

void clearSessionPointersForDisconnect(void* lcnhMaybe) {
    std::lock_guard<std::recursive_mutex> sessionLock(gSessionAccessMutex);
    if (lcnhMaybe == nullptr || gActiveLcnh.load(std::memory_order_acquire) == lcnhMaybe) {
        gActiveLcnh.store(nullptr, std::memory_order_release);
    }
    gActiveLocalPlayer.store(nullptr, std::memory_order_release);
    gLocalPlayerBound.store(false, std::memory_order_release);
    gGameModeBound.store(false, std::memory_order_release);
    gCoordinator.notifyWorldTeardown();
}

void hookLcnhDtorD1(void* self) {
    clearSessionPointersForDisconnect(self);
    if (origLcnhDtorD1) origLcnhDtorD1(self);
}

void hookLcnhDtorD0(void* self) {
    clearSessionPointersForDisconnect(self);
    if (origLcnhDtorD0) origLcnhDtorD0(self);
}

void hookLcnhDisconnect(void* self, const void* netId, const void* packet) {
    clearSessionPointersForDisconnect(self);
    if (origLcnhDisconnect) origLcnhDisconnect(self, netId, packet);
}

void hookLcnhStartGame(void* self, const void* netId, const void* packet) {
    gActiveLcnh.store(self, std::memory_order_release);
    const PacketEvent ev = packet ? abi::decodeStartGamePacket(packet) : PacketEvent{};
    if (origLcnhStartGame) origLcnhStartGame(self, netId, packet);
    if (packet != nullptr) {
        gCoordinator.pushServerPacket(ev);
    }
}

void hookLcnhMovePlayer(void* self, const void* netId, const void* packet) {
    gActiveLcnh.store(self, std::memory_order_release);
    const PacketEvent ev = packet ? abi::decodeMovePlayerPacket(packet) : PacketEvent{};
    if (origLcnhMovePlayer) origLcnhMovePlayer(self, netId, packet);
    if (packet != nullptr) {
        gCoordinator.pushServerPacket(ev);
    }
}

void hookLcnhRespawn(void* self, const void* netId, const void* packet) {
    gActiveLcnh.store(self, std::memory_order_release);
    const PacketEvent ev = packet ? abi::decodeRespawnPacket(packet) : PacketEvent{};
    if (origLcnhRespawn) origLcnhRespawn(self, netId, packet);
    if (packet != nullptr) {
        gCoordinator.pushServerPacket(ev);
    }
}

void hookLcnhChangeDim(void* self, const void* netId, const void* packet) {
    gActiveLcnh.store(self, std::memory_order_release);
    const PacketEvent ev = packet ? abi::decodeChangeDimensionPacket(packet) : PacketEvent{};
    if (origLcnhChangeDim) origLcnhChangeDim(self, netId, packet);
    if (packet != nullptr) {
        gCoordinator.pushServerPacket(ev);
    }
}

void clearLocalPlayerPointer(void* lp) {
    std::lock_guard<std::recursive_mutex> sessionLock(gSessionAccessMutex);
    void* expected = lp;
    if (gActiveLocalPlayer.compare_exchange_strong(expected, nullptr, std::memory_order_acq_rel)) {
        gLocalPlayerBound.store(false, std::memory_order_release);
        gGameModeBound.store(false, std::memory_order_release);
        gCoordinator.notifyWorldTeardown();
    }
}

void hookLocalPlayerDtorD1(void* self) {
    clearLocalPlayerPointer(self);
    if (origLocalPlayerDtorD1) origLocalPlayerDtorD1(self);
}

void hookLocalPlayerDtorD0(void* self) {
    clearLocalPlayerPointer(self);
    if (origLocalPlayerDtorD0) origLocalPlayerDtorD0(self);
}

void hookLocalPlayerNormalTick(void* self) {
    if (origLocalPlayerNormalTick) origLocalPlayerNormalTick(self);

    const auto bias = gLoadBias.load(std::memory_order_acquire);
    if (!isVerifiedLocalPlayer(self, bias)) return;

    using IsClientSideFn = bool (*)(const void*);
    auto isClientSide = resolveFn<IsClientSideFn>(abi::kActorIsClientSide.fnVa);
    if (isClientSide && !isClientSide(self)) return;

    std::lock_guard<std::recursive_mutex> sessionLock(gSessionAccessMutex);
    gActiveLocalPlayer.store(self, std::memory_order_release);
    gLocalPlayerBound.store(true, std::memory_order_release);
    gGameModeBound.store(resolveVerifiedGameMode(self, bias) != nullptr, std::memory_order_release);
    gClientNormalTicks.fetch_add(1, std::memory_order_relaxed);
    gCoordinator.onGameThreadTick(gEngineBackend);
}

void uninstallAllPatchesLocked() {
    for (std::size_t i = gSlotPatchCount; i > 0; --i) {
        gSlotPatches[i - 1].remove();
    }
    gSlotPatchCount = 0;
    gHooksInstalled.store(false, std::memory_order_release);
    {
        std::lock_guard<std::recursive_mutex> sessionLock(gSessionAccessMutex);
        gActiveLcnh.store(nullptr, std::memory_order_release);
        gActiveLocalPlayer.store(nullptr, std::memory_order_release);
        gLocalPlayerBound.store(false, std::memory_order_release);
        gGameModeBound.store(false, std::memory_order_release);
        gCoordinator.disarmAll();
    }
}

template <typename OrigFn>
bool patchSlotLocked(std::uintptr_t bias, const abi::MethodSpec& spec, void* hookFn, OrigFn* origOut) {
    if (gSlotPatchCount >= kMaxPatchedSlots) return false;
    auto* slot = reinterpret_cast<std::uintptr_t*>(
        bias + spec.vtableAddressPointVa + spec.slotIndex * sizeof(std::uintptr_t));
    const std::uintptr_t expected = bias + spec.expectedFnVa;
    if (!gSlotPatches[gSlotPatchCount].install(slot, expected, reinterpret_cast<std::uintptr_t>(hookFn))) {
        return false;
    }
    *origOut = reinterpret_cast<OrigFn>(gSlotPatches[gSlotPatchCount].original());
    ++gSlotPatchCount;
    return true;
}

bool ensureHooksInstalledLocked(const std::string& enginePath, std::string* reasonOut) {
    if (gHooksInstalled.load(std::memory_order_acquire)) {
        return true;
    }
    if (enginePath.empty()) {
        if (reasonOut) *reasonOut = "Engine path missing in :game_host";
        return false;
    }

    const clientbase::LoadedIdentity mod = clientbase::loadedIdentity(enginePath);
    if (!mod.confirmed || mod.machine != EM_AARCH64 || mod.bias == 0) {
        if (reasonOut) *reasonOut = "libminecraftpe.so is not loaded in :game_host yet";
        return false;
    }
    if (mod.bias > UINTPTR_MAX - 0x14000000ULL) {
        if (reasonOut) *reasonOut = "Invalid libminecraftpe.so load bias";
        return false;
    }
    if (mod.buildId != abi::kSupportedBuildId) {
        if (reasonOut) *reasonOut = "Build-ID mismatch (" + mod.buildId + "); hooks refused";
        return false;
    }

    const int memFd = ::open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    if (memFd < 0) {
        if (reasonOut) *reasonOut = "Cannot open /proc/self/mem for pre-hook verification";
        return false;
    }

    const std::uintptr_t bias = mod.bias;
    std::string err;
    const bool verified =
        verifyVtable(memFd, bias, abi::kLoopbackSenderVtable, &err) &&
        verifyVtable(memFd, bias, abi::kLcnhVtable, &err) &&
        verifyVtable(memFd, bias, abi::kGameModeVtable, &err) &&
        verifyVtable(memFd, bias, abi::kSurvivalModeVtable, &err) &&
        verifyVtable(memFd, bias, abi::kLocalPlayerVtable, &err) &&
        verifyVtable(memFd, bias, abi::kRemotePlayerVtable, &err) &&
        verifyVtable(memFd, bias, abi::kPlayerAuthInputVtable, &err) &&
        verifyMethod(memFd, bias, abi::kSenderSendToServer, &err) &&
        verifyMethod(memFd, bias, abi::kLcnhDtorD1, &err) &&
        verifyMethod(memFd, bias, abi::kLcnhDtorD0, &err) &&
        verifyMethod(memFd, bias, abi::kLcnhDisconnect, &err) &&
        verifyMethod(memFd, bias, abi::kLcnhStartGame, &err) &&
        verifyMethod(memFd, bias, abi::kLcnhMovePlayer, &err) &&
        verifyMethod(memFd, bias, abi::kLcnhRespawn, &err) &&
        verifyMethod(memFd, bias, abi::kLcnhChangeDimension, &err) &&
        verifyMethod(memFd, bias, abi::kLocalPlayerDtorD1, &err) &&
        verifyMethod(memFd, bias, abi::kLocalPlayerDtorD0, &err) &&
        verifyMethod(memFd, bias, abi::kLocalPlayerLerpMotion, &err) &&
        verifyMethod(memFd, bias, abi::kLocalPlayerNormalTick, &err) &&
        verifyMethod(memFd, bias, abi::kLocalPlayerIsAlive, &err) &&
        verifyMethod(memFd, bias, abi::kLocalPlayerSwing, &err) &&
        verifyMethod(memFd, bias, abi::kGameModeAttack, &err) &&
        verifyMethod(memFd, bias, abi::kSurvivalModeAttack, &err) &&
        verifyFunction(memFd, bias, abi::kLocalPlayerNormalTickFn, &err) &&
        verifyFunction(memFd, bias, abi::kActorGetRuntimeId, &err) &&
        verifyFunction(memFd, bias, abi::kActorGetUniqueId, &err) &&
        verifyFunction(memFd, bias, abi::kActorGetPosition, &err) &&
        verifyFunction(memFd, bias, abi::kActorCanSee, &err) &&
        verifyFunction(memFd, bias, abi::kActorIsClientSide, &err) &&
        verifyFunction(memFd, bias, abi::kPlayerGetDimensionId, &err);
    ::close(memFd);

    if (!verified) {
        if (reasonOut) *reasonOut = "ABI verification refused: " + err;
        return false;
    }

    gLoadBias.store(bias, std::memory_order_release);
    gSlotPatchCount = 0;

    const bool patched =
        patchSlotLocked(bias, abi::kSenderSendToServer, reinterpret_cast<void*>(&hookSendToServer), &origSendToServer) &&
        patchSlotLocked(bias, abi::kLcnhDtorD1, reinterpret_cast<void*>(&hookLcnhDtorD1), &origLcnhDtorD1) &&
        patchSlotLocked(bias, abi::kLcnhDtorD0, reinterpret_cast<void*>(&hookLcnhDtorD0), &origLcnhDtorD0) &&
        patchSlotLocked(bias, abi::kLcnhDisconnect, reinterpret_cast<void*>(&hookLcnhDisconnect), &origLcnhDisconnect) &&
        patchSlotLocked(bias, abi::kLcnhStartGame, reinterpret_cast<void*>(&hookLcnhStartGame), &origLcnhStartGame) &&
        patchSlotLocked(bias, abi::kLcnhMovePlayer, reinterpret_cast<void*>(&hookLcnhMovePlayer), &origLcnhMovePlayer) &&
        patchSlotLocked(bias, abi::kLcnhRespawn, reinterpret_cast<void*>(&hookLcnhRespawn), &origLcnhRespawn) &&
        patchSlotLocked(bias, abi::kLcnhChangeDimension, reinterpret_cast<void*>(&hookLcnhChangeDim), &origLcnhChangeDim) &&
        patchSlotLocked(bias, abi::kLocalPlayerDtorD1, reinterpret_cast<void*>(&hookLocalPlayerDtorD1), &origLocalPlayerDtorD1) &&
        patchSlotLocked(bias, abi::kLocalPlayerDtorD0, reinterpret_cast<void*>(&hookLocalPlayerDtorD0), &origLocalPlayerDtorD0) &&
        patchSlotLocked(bias, abi::kLocalPlayerNormalTick, reinterpret_cast<void*>(&hookLocalPlayerNormalTick), &origLocalPlayerNormalTick);

    if (!patched) {
        uninstallAllPatchesLocked();
        if (reasonOut) *reasonOut = "Failed to patch RELRO vtable slots via mprotect";
        return false;
    }

    gHooksInstalled.store(true, std::memory_order_release);
    if (reasonOut) *reasonOut = "Verified 1.26.50.4 ARM64 engine · live hooks active";
    return true;
}

std::string buildControlPayload(bool supported, const std::string& message) {
    const CoordinatorSnapshot s = gCoordinator.snapshot();
    const bool hooks = gHooksInstalled.load(std::memory_order_acquire);
    const bool lpBound = gLocalPlayerBound.load(std::memory_order_acquire);
    const bool gmBound = gGameModeBound.load(std::memory_order_acquire);
    const auto normalTicks = gClientNormalTicks.load(std::memory_order_relaxed);

    std::ostringstream out;
    out << "supported=" << (supported ? 1 : 0) << "\n"
        << "hooks_installed=" << (hooks ? 1 : 0) << "\n"
        << "in_world=" << (s.inWorld ? 1 : 0) << "\n"
        << "fly_armed=" << (s.flyDesired ? 1 : 0) << "\n"
        << "fly_active=" << (s.flyActive ? 1 : 0) << "\n"
        << "aura_armed=" << (s.auraDesired ? 1 : 0) << "\n"
        << "aura_active=" << (s.auraActive ? 1 : 0) << "\n"
        << "attack_mobs=" << (s.attackMobs ? 1 : 0) << "\n"
        << "multi_target=" << (s.multiTarget ? 1 : 0) << "\n"
        << "check_hurt_time=" << (s.checkHurtTime ? 1 : 0) << "\n"
        << "self_runtime_id=" << s.selfRuntimeId << "\n"
        << "auth_packets=" << s.authPackets << "\n"
        << "motion_injections=" << s.motionInjections << "\n"
        << "aura_ticks=" << s.auraTicks << "\n"
        << "aura_attacks=" << s.auraAttacks << "\n"
        << "message=" << message << "\n"
        << "---\n"
        << "INTERNAL MODULE ENGINE (26.50 / 1.26.50.4)\n"
        << "PID: " << ::getpid() << " / UID: " << ::getuid() << "\n"
        << "Status: " << message << "\n"
        << "Hooks installed: " << (hooks ? "YES (11 verified RELRO vtable slots)" : "NO") << "\n"
        << "LocalPlayer / GameMode bound: " << (lpBound ? "YES" : "NO")
        << " / " << (gmBound ? "YES" : "NO") << "\n"
        << "World session active: " << (s.inWorld ? "YES" : "NO")
        << " (runtimeId=" << s.selfRuntimeId << ")\n"
        << "Client normalTicks observed: " << normalTicks << "\n"
        << "MotionFly: " << (s.flyActive ? "ON (in-world)" : (s.flyDesired ? "ARMED (waiting for world)" : "OFF"))
        << " · AuthInput=" << s.authPackets << " · MotionSet=" << s.motionInjections << "\n"
        << "KillAura: " << (s.auraActive ? "ON (in-world)" : (s.auraDesired ? "ARMED (waiting for world)" : "OFF"))
        << " · Ticks=" << s.auraTicks << " · Attacks=" << s.auraAttacks << "\n"
        << "KillAura filter: mobs=" << (s.attackMobs ? "on" : "off")
        << " multi=" << (s.multiTarget ? "on" : "off")
        << " hurtCheck=" << (s.checkHurtTime ? "on" : "off") << "\n";
    return out.str();
}

std::string handleControl(const std::string& enginePath, int op,
                          bool flyDesired, bool auraDesired,
                          bool attackMobs, bool multiTarget, bool checkHurtTime) {
    std::lock_guard<std::mutex> lock(gLifecycleMutex);

    if (op == 2) { // DISARM_ALL
        uninstallAllPatchesLocked();
        gStatusMessage = "Disarmed · original RELRO vtable slots restored";
        return buildControlPayload(true, gStatusMessage);
    }

    if (op == 0) { // QUERY
        const clientbase::LoadedIdentity mod = enginePath.empty()
            ? clientbase::LoadedIdentity{}
            : clientbase::loadedIdentity(enginePath);
        const bool ok = mod.confirmed && mod.machine == EM_AARCH64 && mod.buildId == abi::kSupportedBuildId;
        const std::string msg = gHooksInstalled.load(std::memory_order_acquire)
            ? gStatusMessage
            : (ok ? "Engine verified in :game_host · toggle a module to arm live hooks"
                  : "Waiting for verified 1.26.50.4 ARM64 engine in :game_host");
        return buildControlPayload(ok || gHooksInstalled.load(std::memory_order_acquire), msg);
    }

    // op == 1: CONFIGURE / APPLY
    if (!flyDesired && !auraDesired && !gHooksInstalled.load(std::memory_order_acquire)) {
        gCoordinator.configure(false, false, attackMobs, multiTarget, checkHurtTime);
        gStatusMessage = "Modules off";
        const clientbase::LoadedIdentity mod = enginePath.empty()
            ? clientbase::LoadedIdentity{}
            : clientbase::loadedIdentity(enginePath);
        return buildControlPayload(mod.confirmed && mod.machine == EM_AARCH64 && mod.buildId == abi::kSupportedBuildId, gStatusMessage);
    }

    std::string reason;
    if (!ensureHooksInstalledLocked(enginePath, &reason)) {
        gCoordinator.configure(false, false, attackMobs, multiTarget, checkHurtTime);
        gStatusMessage = reason;
        return buildControlPayload(false, gStatusMessage);
    }

    gCoordinator.configure(flyDesired, auraDesired, attackMobs, multiTarget, checkHurtTime);
    const CoordinatorSnapshot snap = gCoordinator.snapshot();
    if (!flyDesired && !auraDesired) {
        gStatusMessage = "Modules off · hooks idle in :game_host";
    } else if (snap.inWorld) {
        gStatusMessage = "Live in world · modules active";
    } else {
        gStatusMessage = "Armed · waiting for LocalPlayer world tick";
    }
    return buildControlPayload(true, gStatusMessage);
}

#else // !defined(__aarch64__)

std::string handleControl(const std::string&, int, bool, bool, bool attackMobs, bool multiTarget, bool checkHurtTime) {
    std::ostringstream out;
    const char* msg = "Unsupported CPU ABI (ARM64 1.26.50.4 required for live hooks)";
    out << "supported=0\n"
        << "hooks_installed=0\n"
        << "in_world=0\n"
        << "fly_armed=0\n"
        << "fly_active=0\n"
        << "aura_armed=0\n"
        << "aura_active=0\n"
        << "attack_mobs=" << (attackMobs ? 1 : 0) << "\n"
        << "multi_target=" << (multiTarget ? 1 : 0) << "\n"
        << "check_hurt_time=" << (checkHurtTime ? 1 : 0) << "\n"
        << "self_runtime_id=0\n"
        << "auth_packets=0\n"
        << "motion_injections=0\n"
        << "aura_ticks=0\n"
        << "aura_attacks=0\n"
        << "message=" << msg << "\n"
        << "---\n"
        << "INTERNAL MODULE ENGINE (26.50 / 1.26.50.4)\n"
        << "Status: " << msg << "\n"
        << "Hooks installed: NO\n";
    return out.str();
}

#endif

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_zips_clientbase_host_EngineNativeBridge_nativeModuleControl(
    JNIEnv* env, jclass, jstring path, jint op,
    jboolean flyDesired, jboolean auraDesired,
    jboolean attackMobs, jboolean multiTarget, jboolean checkHurtTime) {
    std::string enginePath;
    if (path != nullptr) {
        const char* chars = env->GetStringUTFChars(path, nullptr);
        if (chars != nullptr) {
            enginePath.assign(chars);
            env->ReleaseStringUTFChars(path, chars);
        }
    }
    const std::string payload = handleControl(
        enginePath, static_cast<int>(op),
        flyDesired == JNI_TRUE, auraDesired == JNI_TRUE,
        attackMobs == JNI_TRUE, multiTarget == JNI_TRUE, checkHurtTime == JNI_TRUE);
    return env->NewStringUTF(payload.c_str());
}
