#include <jni.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <mutex>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>

#include "loaded_identity.h"
#include "modules/internal_module_coordinator.h"
#include "modules/native_engine_abi.h"
#include "modules/slot_patch.h"

namespace {

using namespace clientbase::modules;

std::mutex gControlMutex;
InternalModuleCoordinator gCoordinator;
std::atomic<bool> gHooksInstalled{false};
std::string gLastMessage = "Idle · enable a module after launching Bedrock";

#if defined(__aarch64__)

std::atomic<std::uintptr_t> gEngineBias{0};

bool readExact(int fd, std::uintptr_t address, void* out, std::size_t bytes) noexcept {
    return pread(fd, out, bytes, static_cast<off_t>(address)) == static_cast<ssize_t>(bytes);
}

bool verifyVtable(int fd, std::uintptr_t bias, const abi::VtableSpec& spec) noexcept {
    std::uintptr_t header[2]{1, 0};
    if (!readExact(fd, bias + spec.addressPoint - 2 * sizeof(std::uintptr_t), header, sizeof(header)))
        return false;
    return header[0] == 0 && header[1] == bias + spec.typeInfo;
}

bool verifyMethod(int fd, std::uintptr_t bias, const abi::MethodSpec& spec) noexcept {
    std::uintptr_t slotTarget = 0;
    const std::uintptr_t slotAddr = bias + spec.vtableAddressPoint + spec.slotIndex * sizeof(std::uintptr_t);
    if (!readExact(fd, slotAddr, &slotTarget, sizeof(slotTarget))) return false;
    if (slotTarget != bias + spec.functionOffset) return false;
    std::uint32_t insns[2]{};
    if (!readExact(fd, slotTarget, insns, sizeof(insns))) return false;
    return insns[0] == spec.insn0 && insns[1] == spec.insn1;
}

bool verifyFunction(int fd, std::uintptr_t bias, const abi::FunctionSpec& spec) noexcept {
    std::uint32_t insns[2]{};
    if (!readExact(fd, bias + spec.functionOffset, insns, sizeof(insns))) return false;
    return insns[0] == spec.insn0 && insns[1] == spec.insn1;
}

using SendToServerFn = void (*)(void*, void*);
using ObjDtorFn = void (*)(void*);
using LcnhHandleFn = void (*)(void*, const void*, const void*);
using GameModeTickFn = void (*)(void*);
using GameModeAttackFn = bool (*)(void*, void*, const Vec3*);
using SetMotionCtorFn = void (*)(void*);
using ActorGetRuntimeIdFn = std::uint64_t (*)(const void*);
using ActorGetUniqueIdFn = const std::int64_t* (*)(const void*);
using ActorGetPositionFn = const Vec3* (*)(const void*);
using ActorGetHurtTimeFn = int (*)(const void*);
using ActorCanSeeFn = bool (*)(const void*, const void*, int);
using PlayerGetDimensionFn = int (*)(const void*);
using ActorLerpMotionFn = void (*)(void*, const Vec3*);
using ActorIsAliveFn = bool (*)(const void*);
using PlayerSwingFn = bool (*)(void*, int, bool);
using LevelGetRuntimeEntityFn = void* (*)(void*, std::uint64_t, bool);
using OperatorDeleteFn = void (*)(void*);
using ClientInstanceGetMcFn = void* (*)(void*);

struct RawActorVector {
    void** begin;
    void** end;
    void** cap;
};
static_assert(sizeof(RawActorVector) == 24, "AAPCS64 indirect result register x8 requires >16B struct");
using LevelGetRuntimeActorsFn = RawActorVector (*)(void*);

SlotPatch gPatches[14];
std::atomic<void*> gCapturedLcnh{nullptr};
std::atomic<void*> gActiveClientGameMode{nullptr};

template <typename Fn>
Fn vtableMethod(const void* object, std::size_t byteOffset) noexcept {
    const auto vtable = abi::readField<std::uintptr_t>(object, 0);
    const auto target = abi::readField<std::uintptr_t>(reinterpret_cast<const void*>(vtable), byteOffset);
    return reinterpret_cast<Fn>(target);
}

bool isLiveLocalPlayer(const void* candidate, std::uintptr_t bias) noexcept {
    if (!candidate || !bias) return false;
    return abi::readField<std::uintptr_t>(candidate, 0) == bias + abi::kLocalPlayerVt.addressPoint;
}

void* currentClientLocalPlayer(std::uintptr_t bias) noexcept {
    void* gm = gActiveClientGameMode.load(std::memory_order_acquire);
    if (!gm || !bias) return nullptr;
    const auto gmVt = abi::readField<std::uintptr_t>(gm, 0);
    if (gmVt != bias + abi::kGameModeVt.addressPoint && gmVt != bias + abi::kSurvivalModeVt.addressPoint)
        return nullptr;
    void* player = abi::readField<void*>(gm, abi::kGameModePlayerOffset);
    return isLiveLocalPlayer(player, bias) ? player : nullptr;
}

void* resolveLegacyClientNetHandler(const void* localPlayer, std::uintptr_t bias) noexcept {
    if (localPlayer && bias) {
        void* ci = abi::readField<void*>(localPlayer, abi::kLocalPlayerClientInstOffset);
        if (ci && abi::readField<std::uintptr_t>(ci, 0) != 0) {
            auto getMc = vtableMethod<ClientInstanceGetMcFn>(ci, abi::kClientInstanceGetMcVtOff);
            void* mc = getMc ? getMc(ci) : nullptr;
            if (mc) {
                void* gs = abi::readField<void*>(mc, abi::kMinecraftGameSessionOffset);
                if (gs) {
                    const void* ctrl = abi::readField<const void*>(gs, abi::kGameSessionNetCbCtrlOffset);
                    void* cb = abi::readField<void*>(gs, abi::kGameSessionNetCbPtrOffset);
                    if (ctrl && abi::readField<std::uint8_t>(ctrl, 0) != 0 && cb
                            && abi::readField<std::uintptr_t>(cb, 0) == bias + abi::kLegacyClientNetVt.addressPoint) {
                        return cb;
                    }
                }
            }
        }
    }
    void* captured = gCapturedLcnh.load(std::memory_order_acquire);
    if (captured && bias && abi::readField<std::uintptr_t>(captured, 0) == bias + abi::kLegacyClientNetVt.addressPoint) {
        return captured;
    }
    return nullptr;
}

class Arm64EngineBackend final : public InternalEngineBackend {
public:
    std::string_view engineBuildId() const noexcept override {
        return gHooksInstalled.load(std::memory_order_acquire) ? supportedEngineBuild : std::string_view{};
    }

    bool canDeliverClientMotion() const noexcept override {
        const auto bias = gEngineBias.load(std::memory_order_acquire);
        if (!bias) return false;
        void* lp = currentClientLocalPlayer(bias);
        return resolveLegacyClientNetHandler(lp, bias) != nullptr || lp != nullptr;
    }

    bool deliverClientMotion(std::uint64_t runtimeId, const Vec3& motion) override {
        const auto bias = gEngineBias.load(std::memory_order_acquire);
        if (!bias || !runtimeId || !motion.finite()) return false;
        void* lp = currentClientLocalPlayer(bias);
        if (void* lcnh = resolveLegacyClientNetHandler(lp, bias)) {
            alignas(16) unsigned char packet[abi::kSetMotionStorageBytes]{};
            alignas(16) unsigned char netId[192]{};
            auto ctor = reinterpret_cast<SetMotionCtorFn>(bias + abi::kSetActorMotionCtor.functionOffset);
            auto handleMotion = reinterpret_cast<LcnhHandleFn>(bias + abi::kLcnhSetActorMotion.functionOffset);
            ctor(packet);
            abi::writeField<std::uint64_t>(packet, abi::kSetMotionRuntimeIdOffset, runtimeId);
            abi::writeField<Vec3>(packet, abi::kSetMotionVecOffset, motion);
            abi::writeField<std::uint64_t>(packet, abi::kSetMotionTickOffset, 0ULL);
            handleMotion(lcnh, netId, packet);
            return true;
        }
        if (lp && abi::readField<void*>(lp, abi::kActorStateVectorOffset) != nullptr) {
            auto getRid = reinterpret_cast<ActorGetRuntimeIdFn>(bias + abi::kActorGetRuntimeId.functionOffset);
            if (getRid(lp) == runtimeId) {
                auto lerp = vtableMethod<ActorLerpMotionFn>(lp, abi::kActorLerpMotionVtOffset);
                if (lerp) {
                    lerp(lp, &motion);
                    return true;
                }
            }
        }
        return false;
    }

    std::optional<LiveSelfState> readLocalPlayer() override {
        const auto bias = gEngineBias.load(std::memory_order_acquire);
        void* lp = currentClientLocalPlayer(bias);
        if (!lp) return std::nullopt;
        if (!abi::readField<void*>(lp, abi::kActorEntityContextMapOffset)
                || !abi::readField<void*>(lp, abi::kActorLevelOffset)
                || !abi::readField<void*>(lp, abi::kActorStateVectorOffset)) {
            return std::nullopt;
        }
        auto getRid = reinterpret_cast<ActorGetRuntimeIdFn>(bias + abi::kActorGetRuntimeId.functionOffset);
        auto getUid = reinterpret_cast<ActorGetUniqueIdFn>(bias + abi::kActorGetUniqueId.functionOffset);
        auto getPos = reinterpret_cast<ActorGetPositionFn>(bias + abi::kActorGetPosition.functionOffset);
        auto getDim = reinterpret_cast<PlayerGetDimensionFn>(bias + abi::kPlayerGetDimension.functionOffset);
        auto isAlive = vtableMethod<ActorIsAliveFn>(lp, abi::kActorIsAliveVtOffset);

        const Vec3* pos = getPos(lp);
        if (!pos || !pos->finite()) return std::nullopt;
        const std::int64_t* uid = getUid(lp);
        const void* rotComp = abi::readField<const void*>(lp, abi::kActorRotationOffset);

        LiveSelfState state;
        state.runtimeId = getRid(lp);
        state.uniqueId = uid ? *uid : -1;
        state.position = *pos;
        state.pitch = rotComp ? abi::readField<float>(rotComp, 0) : 0.0f;
        state.yaw = rotComp ? abi::readField<float>(rotComp, sizeof(float)) : 0.0f;
        state.dimension = getDim(lp);
        state.alive = isAlive ? isAlive(lp) : false;
        return state;
    }

    std::vector<Target> collectTargets(const LiveSelfState& self, float maxRange) override {
        std::vector<Target> out;
        const auto bias = gEngineBias.load(std::memory_order_acquire);
        void* lp = currentClientLocalPlayer(bias);
        if (!lp) return out;
        void* level = abi::readField<void*>(lp, abi::kActorLevelOffset);
        if (!level || abi::readField<std::uintptr_t>(level, 0) == 0) return out;

        auto getActors = vtableMethod<LevelGetRuntimeActorsFn>(level, abi::kLevelGetRuntimeActorsVtOff);
        auto engineDelete = reinterpret_cast<OperatorDeleteFn>(bias + abi::kEngineOperatorDelete);
        if (!getActors) return out;

        const RawActorVector raw = getActors(level);
        if (!raw.begin || raw.end < raw.begin) return out;

        const std::size_t count = static_cast<std::size_t>(raw.end - raw.begin);
        const std::size_t capped = count > 512 ? 512 : count;
        std::vector<void*> snapshot(raw.begin, raw.begin + capped);
        engineDelete(raw.begin);

        auto getRid = reinterpret_cast<ActorGetRuntimeIdFn>(bias + abi::kActorGetRuntimeId.functionOffset);
        auto getPos = reinterpret_cast<ActorGetPositionFn>(bias + abi::kActorGetPosition.functionOffset);
        auto getHurt = reinterpret_cast<ActorGetHurtTimeFn>(bias + abi::kActorGetHurtTime.functionOffset);
        auto canSee = reinterpret_cast<ActorCanSeeFn>(bias + abi::kActorCanSee.functionOffset);
        const bool canCheckSight = abi::readField<void*>(lp, abi::kActorDimensionPtrOffset) != nullptr
            && abi::readField<void*>(lp, abi::kActorDimensionWeakOffset) != nullptr;

        out.reserve(snapshot.size());
        for (void* actor : snapshot) {
            if (!actor || actor == lp) continue;
            if (!abi::readField<void*>(actor, abi::kActorEntityContextMapOffset)
                    || !abi::readField<void*>(actor, abi::kActorStateVectorOffset)
                    || !abi::readField<void*>(actor, abi::kActorLevelOffset)) {
                continue;
            }
            const auto categories = abi::readField<std::uint32_t>(actor, abi::kActorCategoriesOffset);
            const bool isPlayer = (categories & abi::kActorCategoryPlayer) != 0;
            const bool isMob = (categories & abi::kActorCategoryMob) != 0;
            if (!isPlayer && !isMob) continue;

            const Vec3* pos = getPos(actor);
            if (!pos || !pos->finite()) continue;
            if (distance(self.position, *pos) > static_cast<double>(maxRange)) continue;

            const std::uint64_t rid = getRid(actor);
            if (!rid || rid == self.runtimeId) continue;

            auto isAlive = vtableMethod<ActorIsAliveFn>(actor, abi::kActorIsAliveVtOffset);
            const bool alive = isAlive ? isAlive(actor) : false;
            if (!alive) continue;

            const bool visible = canCheckSight ? canSee(lp, actor, abi::kCanSeeShapeCollision) : true;
            const int hurtTicks = getHurt(actor);
            out.push_back(Target{rid, *pos, isPlayer, alive, false, true, visible, hurtTicks});
        }
        return out;
    }

    bool attackTarget(std::uint64_t expectedSelfRuntimeId, std::uint64_t targetRuntimeId) override {
        const auto bias = gEngineBias.load(std::memory_order_acquire);
        void* gm = gActiveClientGameMode.load(std::memory_order_acquire);
        void* lp = currentClientLocalPlayer(bias);
        if (!gm || !lp || !expectedSelfRuntimeId || !targetRuntimeId) return false;

        auto getRid = reinterpret_cast<ActorGetRuntimeIdFn>(bias + abi::kActorGetRuntimeId.functionOffset);
        if (getRid(lp) != expectedSelfRuntimeId) return false;
        auto lpAlive = vtableMethod<ActorIsAliveFn>(lp, abi::kActorIsAliveVtOffset);
        if (!lpAlive || !lpAlive(lp)) return false;

        void* level = abi::readField<void*>(lp, abi::kActorLevelOffset);
        if (!level) return false;
        auto getEntity = vtableMethod<LevelGetRuntimeEntityFn>(level, abi::kLevelGetRuntimeEntityVtOff);
        if (!getEntity) return false;

        void* target = getEntity(level, targetRuntimeId, false);
        if (!target || target == lp || !abi::readField<void*>(target, abi::kActorStateVectorOffset)) return false;
        auto targetAlive = vtableMethod<ActorIsAliveFn>(target, abi::kActorIsAliveVtOffset);
        if (!targetAlive || !targetAlive(target)) return false;

        auto getPos = reinterpret_cast<ActorGetPositionFn>(bias + abi::kActorGetPosition.functionOffset);
        const Vec3* rawPos = getPos(target);
        if (!rawPos || !rawPos->finite()) return false;
        Vec3 hitPos{rawPos->x, rawPos->y + 0.5f, rawPos->z};

        auto attack = vtableMethod<GameModeAttackFn>(gm, abi::kGameModeAttackVtOffset);
        return attack ? attack(gm, target, &hitPos) : false;
    }

    void swingArm(std::uint64_t expectedSelfRuntimeId) override {
        const auto bias = gEngineBias.load(std::memory_order_acquire);
        void* lp = currentClientLocalPlayer(bias);
        if (!lp || !expectedSelfRuntimeId) return;
        auto getRid = reinterpret_cast<ActorGetRuntimeIdFn>(bias + abi::kActorGetRuntimeId.functionOffset);
        if (getRid(lp) != expectedSelfRuntimeId) return;
        auto swing = vtableMethod<PlayerSwingFn>(lp, abi::kPlayerSwingVtOffset);
        if (swing) swing(lp, abi::kActorSwingSourceAttack, false);
    }
};

Arm64EngineBackend gBackend;

void hookSendToServer(void* sender, void* packet) {
    auto orig = reinterpret_cast<SendToServerFn>(gPatches[0].original());
    const auto bias = gEngineBias.load(std::memory_order_relaxed);
    if (!sender || !packet || !bias
            || abi::readField<std::uint32_t>(sender, abi::kLoopbackSenderKindOffset) != 0U
            || abi::readField<std::uintptr_t>(packet, 0) != bias + abi::kAuthInputPacketVt.addressPoint) {
        orig(sender, packet);
        return;
    }
    const PacketEvent auth = abi::decodeAuthInputPacket(packet);
    orig(sender, packet);
    gCoordinator.onOutboundAuthInput(gBackend, auth);
}

void clearLcnh(void* handler) noexcept {
    void* expected = handler;
    gCapturedLcnh.compare_exchange_strong(expected, nullptr, std::memory_order_acq_rel);
    gCoordinator.notifyWorldTeardown();
}

void hookLcnhDtorComplete(void* self) {
    clearLcnh(self);
    reinterpret_cast<ObjDtorFn>(gPatches[1].original())(self);
}

void hookLcnhDtorDeleting(void* self) {
    clearLcnh(self);
    reinterpret_cast<ObjDtorFn>(gPatches[2].original())(self);
}

void hookLcnhDisconnect(void* self, const void* netId, const void* packet) {
    clearLcnh(self);
    reinterpret_cast<LcnhHandleFn>(gPatches[3].original())(self, netId, packet);
}

void hookLcnhStartGame(void* self, const void* netId, const void* packet) {
    gCapturedLcnh.store(self, std::memory_order_release);
    const PacketEvent ev = packet ? abi::decodeStartGamePacket(packet) : PacketEvent{};
    reinterpret_cast<LcnhHandleFn>(gPatches[4].original())(self, netId, packet);
    if (packet) gCoordinator.pushServerPacket(ev);
}

void hookLcnhMovePlayer(void* self, const void* netId, const void* packet) {
    gCapturedLcnh.store(self, std::memory_order_release);
    const PacketEvent ev = packet ? abi::decodeMovePlayerPacket(packet) : PacketEvent{};
    reinterpret_cast<LcnhHandleFn>(gPatches[5].original())(self, netId, packet);
    if (packet) gCoordinator.pushServerPacket(ev);
}

void hookLcnhChangeDimension(void* self, const void* netId, const void* packet) {
    gCapturedLcnh.store(self, std::memory_order_release);
    const PacketEvent ev = packet ? abi::decodeChangeDimensionPacket(packet) : PacketEvent{};
    reinterpret_cast<LcnhHandleFn>(gPatches[6].original())(self, netId, packet);
    if (packet) gCoordinator.pushServerPacket(ev);
}

void hookLcnhRespawn(void* self, const void* netId, const void* packet) {
    gCapturedLcnh.store(self, std::memory_order_release);
    const PacketEvent ev = packet ? abi::decodeRespawnPacket(packet) : PacketEvent{};
    reinterpret_cast<LcnhHandleFn>(gPatches[7].original())(self, netId, packet);
    if (packet) gCoordinator.pushServerPacket(ev);
}

void clearGameModeIfActive(void* gm) noexcept {
    void* expected = gm;
    if (gActiveClientGameMode.compare_exchange_strong(expected, nullptr, std::memory_order_acq_rel)) {
        gCoordinator.notifyWorldTeardown();
    }
}

void runClientTickIfLocalPlayer(void* gm) {
    const auto bias = gEngineBias.load(std::memory_order_relaxed);
    if (!gm || !bias) return;
    void* player = abi::readField<void*>(gm, abi::kGameModePlayerOffset);
    if (!isLiveLocalPlayer(player, bias)) return; // Ignore integrated-server ServerPlayer ticks.
    gActiveClientGameMode.store(gm, std::memory_order_release);
    gCoordinator.onGameThreadTick(gBackend);
}

void hookGameModeDtorComplete(void* gm) {
    clearGameModeIfActive(gm);
    reinterpret_cast<ObjDtorFn>(gPatches[8].original())(gm);
}

void hookGameModeDtorDeleting(void* gm) {
    clearGameModeIfActive(gm);
    reinterpret_cast<ObjDtorFn>(gPatches[9].original())(gm);
}

void hookGameModeTick(void* gm) {
    reinterpret_cast<GameModeTickFn>(gPatches[10].original())(gm);
    runClientTickIfLocalPlayer(gm);
}

void hookSurvivalDtorComplete(void* gm) {
    clearGameModeIfActive(gm);
    reinterpret_cast<ObjDtorFn>(gPatches[11].original())(gm);
}

void hookSurvivalDtorDeleting(void* gm) {
    clearGameModeIfActive(gm);
    reinterpret_cast<ObjDtorFn>(gPatches[12].original())(gm);
}

void hookSurvivalTick(void* gm) {
    reinterpret_cast<GameModeTickFn>(gPatches[13].original())(gm);
    runClientTickIfLocalPlayer(gm);
}

bool ensureHooksInstalledLocked(const std::string& enginePath) {
    if (gHooksInstalled.load(std::memory_order_acquire)) return true;

    const auto id = clientbase::loadedIdentity(enginePath);
    if (!id.confirmed || id.machine != EM_AARCH64 || id.buildId != supportedEngineBuild) {
        gLastMessage = "Unavailable · verified ARM64 Minecraft 26.50 engine is not loaded";
        return false;
    }
    if (id.bias > UINTPTR_MAX - 0x14000000ULL) {
        gLastMessage = "Unavailable · invalid engine load bias";
        return false;
    }

    const int fd = open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        gLastMessage = "Unavailable · own-process memory verification failed";
        return false;
    }

    const abi::MethodSpec hookedSpecs[14] = {
        abi::kLoopbackSendToServer,
        abi::kLcnhDtorComplete, abi::kLcnhDtorDeleting, abi::kLcnhDisconnect,
        abi::kLcnhStartGame, abi::kLcnhMovePlayer, abi::kLcnhChangeDimension, abi::kLcnhRespawn,
        abi::kGameModeDtorComplete, abi::kGameModeDtorDeleting, abi::kGameModeTick,
        abi::kSurvivalDtorComplete, abi::kSurvivalDtorDeleting, abi::kSurvivalTick
    };
    const abi::MethodSpec extraMethodSpecs[] = {
        abi::kLcnhSetActorMotion, abi::kGameModeAttack, abi::kSurvivalAttack
    };
    const abi::FunctionSpec helperSpecs[] = {
        abi::kSetActorMotionCtor, abi::kActorGetRuntimeId, abi::kActorGetUniqueId,
        abi::kActorGetPosition, abi::kActorGetHurtTime, abi::kActorCanSee, abi::kPlayerGetDimension
    };

    bool verified = true;
    for (const auto& vt : abi::kRequiredVtables) verified = verified && verifyVtable(fd, id.bias, vt);
    for (const auto& m : hookedSpecs) verified = verified && verifyMethod(fd, id.bias, m);
    for (const auto& m : extraMethodSpecs) verified = verified && verifyMethod(fd, id.bias, m);
    for (const auto& fn : helperSpecs) verified = verified && verifyFunction(fd, id.bias, fn);
    close(fd);

    if (!verified) {
        gLastMessage = "Unavailable · engine RTTI/vtable/instruction fingerprint mismatch";
        return false;
    }

    const std::uintptr_t replacements[14] = {
        reinterpret_cast<std::uintptr_t>(&hookSendToServer),
        reinterpret_cast<std::uintptr_t>(&hookLcnhDtorComplete),
        reinterpret_cast<std::uintptr_t>(&hookLcnhDtorDeleting),
        reinterpret_cast<std::uintptr_t>(&hookLcnhDisconnect),
        reinterpret_cast<std::uintptr_t>(&hookLcnhStartGame),
        reinterpret_cast<std::uintptr_t>(&hookLcnhMovePlayer),
        reinterpret_cast<std::uintptr_t>(&hookLcnhChangeDimension),
        reinterpret_cast<std::uintptr_t>(&hookLcnhRespawn),
        reinterpret_cast<std::uintptr_t>(&hookGameModeDtorComplete),
        reinterpret_cast<std::uintptr_t>(&hookGameModeDtorDeleting),
        reinterpret_cast<std::uintptr_t>(&hookGameModeTick),
        reinterpret_cast<std::uintptr_t>(&hookSurvivalDtorComplete),
        reinterpret_cast<std::uintptr_t>(&hookSurvivalDtorDeleting),
        reinterpret_cast<std::uintptr_t>(&hookSurvivalTick)
    };

    gEngineBias.store(id.bias, std::memory_order_release);
    for (std::size_t i = 0; i < 14; ++i) {
        auto* slot = reinterpret_cast<std::uintptr_t*>(
            id.bias + hookedSpecs[i].vtableAddressPoint + hookedSpecs[i].slotIndex * sizeof(std::uintptr_t));
        const std::uintptr_t expected = id.bias + hookedSpecs[i].functionOffset;
        if (!gPatches[i].install(slot, expected, replacements[i])) {
            const std::string err = gPatches[i].error();
            for (std::size_t j = 0; j < i; ++j) gPatches[j].remove();
            gEngineBias.store(0, std::memory_order_release);
            gLastMessage = "Unavailable · RELRO hook failed: " + err;
            return false;
        }
    }

    gHooksInstalled.store(true, std::memory_order_release);
    gLastMessage = "Live internal bindings active for Minecraft 26.50";
    return true;
}

bool removeHooksLocked() {
    gCoordinator.disarmAll();
    gActiveClientGameMode.store(nullptr, std::memory_order_release);
    gCapturedLcnh.store(nullptr, std::memory_order_release);
    if (!gHooksInstalled.load(std::memory_order_acquire)) {
        gLastMessage = "Disarmed · no hooks installed";
        return true;
    }
    bool allRemoved = true;
    for (auto& patch : gPatches) {
        if (!patch.remove()) allRemoved = false;
    }
    if (allRemoved) {
        gHooksInstalled.store(false, std::memory_order_release);
        gEngineBias.store(0, std::memory_order_release);
        gLastMessage = "Disarmed · all RELRO slots restored";
    } else {
        gLastMessage = "Disarm warning · restart Bedrock to reset all slots";
    }
    return allRemoved;
}

constexpr bool kCompiledForArm64 = true;

#else

bool ensureHooksInstalledLocked(const std::string&) {
    gLastMessage = "Unavailable · ARM64 Minecraft 26.50 runtime required";
    return false;
}

bool removeHooksLocked() {
    gCoordinator.disarmAll();
    gLastMessage = "Disarmed · non-ARM64 build";
    return true;
}

constexpr bool kCompiledForArm64 = false;

#endif

std::string buildResponseLocked() {
    const auto snap = gCoordinator.snapshot();
    const bool hooks = gHooksInstalled.load(std::memory_order_acquire);
    const bool flyArmed = hooks && snap.flyDesired;
    const bool auraArmed = hooks && snap.auraDesired;

    std::ostringstream out;
    out << "supported=" << (kCompiledForArm64 ? 1 : 0) << "\n"
        << "hooks_installed=" << (hooks ? 1 : 0) << "\n"
        << "in_world=" << (hooks && snap.inWorld ? 1 : 0) << "\n"
        << "fly_armed=" << (flyArmed ? 1 : 0) << "\n"
        << "fly_active=" << (flyArmed && snap.flyActive ? 1 : 0) << "\n"
        << "aura_armed=" << (auraArmed ? 1 : 0) << "\n"
        << "aura_active=" << (auraArmed && snap.auraActive ? 1 : 0) << "\n"
        << "attack_mobs=" << (snap.attackMobs ? 1 : 0) << "\n"
        << "multi_target=" << (snap.multiTarget ? 1 : 0) << "\n"
        << "check_hurt_time=" << (snap.checkHurtTime ? 1 : 0) << "\n"
        << "self_runtime_id=" << snap.selfRuntimeId << "\n"
        << "auth_packets=" << snap.authPackets << "\n"
        << "motion_injections=" << snap.motionInjections << "\n"
        << "aura_ticks=" << snap.auraTicks << "\n"
        << "aura_attacks=" << snap.auraAttacks << "\n"
        << "message=" << gLastMessage << "\n"
        << "---\n"
        << "INTERNAL MODULE ENGINE (" << (kCompiledForArm64 ? "ARM64" : "NON-ARM64") << ")\n"
        << "PID: " << getpid() << "  UID: " << getuid() << "\n"
        << "Status: " << gLastMessage << "\n"
        << "Hooks installed: " << (hooks ? "YES (14 RELRO vtable slots, 0 .text edits)" : "NO") << "\n"
        << "World session: " << (hooks && snap.inWorld ? "ACTIVE" : "WAITING / NOT IN WORLD")
        << "  Self runtime ID: " << snap.selfRuntimeId << "\n"
        << "MotionFly (internal packet path): "
        << (flyArmed ? (snap.flyActive ? "ON (live in world)" : "ARMED (waiting for world)") : "OFF")
        << "  [AuthInput=" << snap.authPackets << ", SetActorMotion=" << snap.motionInjections << "]\n"
        << "KillAura (native GameMode path): "
        << (auraArmed ? (snap.auraActive ? "ON (live in world)" : "ARMED (waiting for world)") : "OFF")
        << "  [Ticks=" << snap.auraTicks << ", Attacks=" << snap.auraAttacks
        << ", Mobs=" << (snap.attackMobs ? "ON" : "OFF")
        << ", Multi=" << (snap.multiTarget ? "ON" : "OFF")
        << ", HurtTime=" << (snap.checkHurtTime ? "ON" : "OFF") << "]\n";
    return out.str();
}

std::string handleControl(const std::string& enginePath, int op,
                          bool flyDesired, bool auraDesired,
                          bool attackMobs, bool multiTarget, bool checkHurtTime) {
    std::lock_guard<std::mutex> lock(gControlMutex);
    if (op == 2) {
        removeHooksLocked();
        return buildResponseLocked();
    }
    if (op == 1) {
        if (flyDesired || auraDesired) {
            if (!ensureHooksInstalledLocked(enginePath)) {
                gCoordinator.configure(false, false, attackMobs, multiTarget, checkHurtTime);
                return buildResponseLocked();
            }
        }
        gCoordinator.configure(flyDesired, auraDesired, attackMobs, multiTarget, checkHurtTime);
        if (!flyDesired && !auraDesired && gHooksInstalled.load(std::memory_order_acquire)) {
            gLastMessage = "Modules off · verified 26.50 bindings ready";
        } else if (gHooksInstalled.load(std::memory_order_acquire)) {
            gLastMessage = gCoordinator.snapshot().inWorld
                ? "Live in world · internal handling active"
                : "Armed · enter a world to activate";
        }
    }
    return buildResponseLocked();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_zips_clientbase_host_EngineNativeBridge_nativeModuleControl(
        JNIEnv* env, jclass, jstring path, jint op,
        jboolean flyDesired, jboolean auraDesired,
        jboolean attackMobs, jboolean multiTarget, jboolean checkHurtTime) {
    const char* raw = env->GetStringUTFChars(path, nullptr);
    if (!raw) return nullptr;
    std::string enginePath(raw);
    env->ReleaseStringUTFChars(path, raw);
    const std::string response = handleControl(
        enginePath, static_cast<int>(op),
        flyDesired == JNI_TRUE, auraDesired == JNI_TRUE,
        attackMobs == JNI_TRUE, multiTarget == JNI_TRUE, checkHurtTime == JNI_TRUE);
    return env->NewStringUTF(response.c_str());
}
