#include "internal_module_coordinator.h"
#include "native_engine_abi.h"
#include <cassert>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

using namespace clientbase::modules;

namespace {

struct FakeEngine final : public InternalEngineBackend {
    std::string_view build{supportedEngineBuild};
    bool motionAvailable{true};
    std::optional<LiveSelfState> selfState;
    std::vector<Target> worldActors;
    std::vector<std::pair<std::uint64_t, Vec3>> deliveredMotions;
    std::vector<std::uint64_t> attackedTargets;
    unsigned swings{};
    bool removeFirstTargetDuringAttack{};

    std::string_view engineBuildId() const noexcept override { return build; }
    bool canDeliverClientMotion() const noexcept override { return motionAvailable; }

    bool deliverClientMotion(std::uint64_t runtimeId, const Vec3& motion) override {
        if (!motionAvailable) return false;
        deliveredMotions.push_back({runtimeId, motion});
        return true;
    }

    std::optional<LiveSelfState> readLocalPlayer() override { return selfState; }

    std::vector<Target> collectTargets(const LiveSelfState&, float maxRange) override {
        std::vector<Target> filtered;
        if (!selfState) return filtered;
        for (const auto& t : worldActors) {
            if (distance(selfState->position, t.position) <= maxRange) filtered.push_back(t);
        }
        return filtered;
    }

    bool attackTarget(std::uint64_t expectedSelfRuntimeId, std::uint64_t targetRuntimeId) override {
        if (!selfState || selfState->runtimeId != expectedSelfRuntimeId || !selfState->alive) return false;
        bool exists = false;
        for (const auto& t : worldActors) {
            if (t.id == targetRuntimeId && t.alive) {
                exists = true;
                break;
            }
        }
        if (!exists) return false;
        attackedTargets.push_back(targetRuntimeId);
        if (removeFirstTargetDuringAttack && !worldActors.empty()) {
            worldActors.erase(worldActors.begin());
        }
        return true;
    }

    void swingArm(std::uint64_t expectedSelfRuntimeId) override {
        assert(selfState && selfState->runtimeId == expectedSelfRuntimeId);
        ++swings;
    }
};

void testBinaryPacketDecoders() {
    alignas(16) unsigned char authBuf[256]{};
    abi::writeField<float>(authBuf, abi::kAuthInputRotOffset, 12.5f);
    abi::writeField<float>(authBuf, abi::kAuthInputRotOffset + 4, 90.0f);
    abi::writeField<Vec3>(authBuf, abi::kAuthInputPosOffset, Vec3{10.f, 70.f, -20.f});
    abi::writeField<float>(authBuf, abi::kAuthInputMoveOffset, 0.0f);
    abi::writeField<float>(authBuf, abi::kAuthInputMoveOffset + 4, 1.0f);
    const std::uint64_t bits = (1ULL << abi::kAuthBitWantUp) | (1ULL << abi::kAuthBitSneaking);
    abi::writeField<std::uint64_t>(authBuf, abi::kAuthInputBitsOffset, bits);

    const PacketEvent auth = abi::decodeAuthInputPacket(authBuf);
    assert(auth.kind == PacketEventKind::AuthInput);
    assert(auth.direction == Direction::ClientToServer);
    assert(auth.pitch == 12.5f && auth.yaw == 90.0f);
    assert(auth.position.x == 10.f && auth.position.y == 70.f && auth.position.z == -20.f);
    assert(auth.input.strafe == 0.0f && auth.input.forward == 1.0f);
    assert(auth.input.wantUp && auth.input.sneaking && !auth.input.jumping && !auth.input.wantDown);

    alignas(16) unsigned char sgBuf[1536]{};
    abi::writeField<std::int32_t>(sgBuf, abi::kStartGameDimensionOffset, 1);
    abi::writeField<std::int64_t>(sgBuf, abi::kStartGameUniqueIdOffset, -999);
    abi::writeField<std::uint64_t>(sgBuf, abi::kStartGameRuntimeIdOffset, 77);
    abi::writeField<Vec3>(sgBuf, abi::kStartGamePosOffset, Vec3{1.f, 65.f, 2.f});
    abi::writeField<float>(sgBuf, abi::kStartGameRotOffset, 5.f);
    abi::writeField<float>(sgBuf, abi::kStartGameRotOffset + 4, 180.f);
    const PacketEvent sg = abi::decodeStartGamePacket(sgBuf);
    assert(sg.kind == PacketEventKind::StartGame && sg.runtimeId == 77 && sg.uniqueId == -999 && sg.dimension == 1);
    assert(sg.yaw == 180.f && sg.pitch == 5.f);

    alignas(16) unsigned char respawnBuf[128]{};
    abi::writeField<Vec3>(respawnBuf, abi::kRespawnPosOffset, Vec3{4.f, 80.f, 6.f});
    abi::writeField<std::uint8_t>(respawnBuf, abi::kRespawnStateOffset, 0);
    assert(abi::decodeRespawnPacket(respawnBuf).respawnPositionReady);
    abi::writeField<std::uint8_t>(respawnBuf, abi::kRespawnStateOffset, 2);
    assert(!abi::decodeRespawnPacket(respawnBuf).respawnPositionReady);
}

void testMidWorldFlyAndCrossThreadQueueAndCombat() {
    FakeEngine engine;
    InternalModuleCoordinator coordinator;

    // User already entered a world before enabling MotionFly & KillAura (no StartGame packet hook seen).
    engine.selfState = LiveSelfState{55, -1055, Vec3{0.f, 70.f, 0.f}, 0.f, 0.f, 0, true};
    engine.worldActors = {
        Target{101, Vec3{3.f, 70.f, 0.f}, false, true, false, true, true, 0}, // mob at 3m
        Target{102, Vec3{4.f, 70.f, 0.f}, true, true, false, true, true, 5}   // player at 4m with hurtTime=5
    };

    coordinator.configure(true, true, /*attackMobs=*/true, /*multi=*/false, /*checkHurtTime=*/false);
    coordinator.onGameThreadTick(engine); // tick 1 (delay 0 -> 1)
    auto snap = coordinator.snapshot();
    assert(snap.inWorld && snap.flyActive && snap.auraActive && snap.selfRuntimeId == 55);
    assert(engine.attackedTargets.empty());

    // Immediate same-thread AuthInput emits clientbound SetActorMotion right away.
    alignas(16) unsigned char authBuf[256]{};
    abi::writeField<float>(authBuf, abi::kAuthInputRotOffset + 4, 0.0f);
    abi::writeField<Vec3>(authBuf, abi::kAuthInputPosOffset, Vec3{0.2f, 70.f, 0.f});
    abi::writeField<float>(authBuf, abi::kAuthInputMoveOffset + 4, 1.0f);
    abi::writeField<std::uint64_t>(authBuf, abi::kAuthInputBitsOffset, 1ULL << abi::kAuthBitJumping);
    coordinator.onOutboundAuthInput(engine, abi::decodeAuthInputPacket(authBuf));
    assert(engine.deliveredMotions.size() == 1);
    assert(engine.deliveredMotions[0].first == 55);
    assert(engine.deliveredMotions[0].second.y > 0.9f);

    // Worker-thread AuthInput queues safely and executes on the game thread tick without failing owner checks.
    std::thread worker([&]() {
        coordinator.onOutboundAuthInput(engine, abi::decodeAuthInputPacket(authBuf));
    });
    worker.join();
    assert(engine.deliveredMotions.size() == 1); // queued until game-thread tick

    coordinator.onGameThreadTick(engine); // tick 2 (drains worker AuthInput + aura delay 1 -> 2)
    assert(engine.deliveredMotions.size() == 2);
    assert(engine.attackedTargets.empty());

    coordinator.onGameThreadTick(engine); // tick 3 -> attacks nearest mob 101 and swings!
    assert(engine.attackedTargets.size() == 1 && engine.attackedTargets[0] == 101);
    assert(engine.swings == 1);

    // Switch to players-only + hurtTime check: skips mob 101 and skips hurt player 102 until hurtTicks == 0.
    coordinator.configure(true, true, /*attackMobs=*/false, /*multi=*/false, /*checkHurtTime=*/true);
    coordinator.onGameThreadTick(engine); // delay 0 -> 1
    coordinator.onGameThreadTick(engine); // delay 1 -> 2
    coordinator.onGameThreadTick(engine); // eligible tick, but player 102 has hurtTicks=5 -> 0 attacks
    assert(engine.attackedTargets.size() == 1);

    engine.worldActors[1].hurtTicks = 0;
    coordinator.onGameThreadTick(engine); // delay 0 -> 1
    coordinator.onGameThreadTick(engine); // delay 1 -> 2
    coordinator.onGameThreadTick(engine); // attacks player 102!
    assert(engine.attackedTargets.size() == 2 && engine.attackedTargets[1] == 102);
    assert(engine.swings == 2);

    // World disconnect resets active flags immediately, then cleanly rebinds on a different game thread.
    coordinator.notifyWorldTeardown();
    snap = coordinator.snapshot();
    assert(!snap.inWorld && !snap.flyActive && !snap.auraActive);

    std::thread nextWorldThread([&]() {
        engine.selfState = LiveSelfState{88, -1088, Vec3{5.f, 64.f, 5.f}, 0.f, 0.f, 0, true};
        coordinator.onGameThreadTick(engine);
        const auto s2 = coordinator.snapshot();
        assert(s2.inWorld && s2.flyActive && s2.auraActive && s2.selfRuntimeId == 88);
    });
    nextWorldThread.join();
}

} // namespace

int main() {
    testBinaryPacketDecoders();
    testMidWorldFlyAndCrossThreadQueueAndCombat();
    return 0;
}
