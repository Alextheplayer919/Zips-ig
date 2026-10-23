#pragma once

#include "module_runtime.h"
#include <atomic>
#include <cmath>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <optional>
#include <string_view>
#include <thread>
#include <vector>

namespace clientbase::modules {

struct LiveSelfState {
    std::uint64_t runtimeId{};
    std::int64_t uniqueId{};
    Vec3 position{};
    float yaw{}, pitch{};
    int dimension{};
    bool alive{};
};

// Engine-side operations implemented by the verified ARM64 bridge (or native unit-test harness).
class InternalEngineBackend {
public:
    virtual ~InternalEngineBackend() = default;
    virtual std::string_view engineBuildId() const noexcept = 0;
    virtual bool canDeliverClientMotion() const noexcept = 0;
    virtual bool deliverClientMotion(std::uint64_t runtimeId, const Vec3& motion) = 0;
    virtual std::optional<LiveSelfState> readLocalPlayer() = 0;
    virtual std::vector<Target> collectTargets(const LiveSelfState& self, float maxRange) = 0;
    virtual bool attackTarget(std::uint64_t expectedSelfRuntimeId, std::uint64_t targetRuntimeId) = 0;
    virtual void swingArm(std::uint64_t expectedSelfRuntimeId) = 0;
};

struct CoordinatorSnapshot {
    bool inWorld{};
    bool flyDesired{};
    bool flyActive{};
    bool auraDesired{};
    bool auraActive{};
    bool attackMobs{true};
    bool multiTarget{};
    bool checkHurtTime{};
    std::uint64_t selfRuntimeId{};
    std::uint64_t authPackets{};
    std::uint64_t motionInjections{};
    std::uint64_t auraTicks{};
    std::uint64_t auraAttacks{};
};

// Coordinates decoded packet events and game-thread ticks into FlyPacketRuntime & KillAuraRuntime.
// UI/IPC callers touch ONLY lock-free atomics; all runtime/engine calls execute on gameThread_.
class InternalModuleCoordinator {
    class SessionAdapter final : public PacketSession {
        InternalEngineBackend* backend_{};
        bool open_{};
    public:
        void bind(InternalEngineBackend* backend, bool isOpen) noexcept {
            backend_ = backend;
            open_ = isOpen;
        }
        bool compatible() const noexcept override {
            return backend_ && backend_->engineBuildId() == supportedEngineBuild;
        }
        bool open() const noexcept override {
            return open_ && backend_ && backend_->canDeliverClientMotion();
        }
        bool clientBoundMotion(std::uint64_t runtimeId, const Vec3& motion) override {
            return open() && backend_->deliverClientMotion(runtimeId, motion);
        }
    };

    class CombatAdapter final : public NativeCombatAdapter {
        InternalEngineBackend* backend_{};
        std::thread::id owner_{};
        std::uint64_t worldSession_{};
        float maxRange_{8.0f};
    public:
        void configure(InternalEngineBackend* backend, std::thread::id owner,
                       std::uint64_t worldSession, float maxRange) noexcept {
            backend_ = backend;
            owner_ = owner;
            worldSession_ = worldSession;
            maxRange_ = maxRange;
        }
        std::string_view engineBuildId() const noexcept override {
            return backend_ ? backend_->engineBuildId() : std::string_view{};
        }
        bool onGameThread() const noexcept override {
            return owner_ != std::thread::id{} && std::this_thread::get_id() == owner_;
        }
        std::optional<CombatFrame> frame() override {
            if (!backend_ || !onGameThread() || worldSession_ == 0) return std::nullopt;
            const auto selfState = backend_->readLocalPlayer();
            if (!selfState || !selfState->alive || selfState->runtimeId == 0 || !selfState->position.finite())
                return std::nullopt;
            CombatFrame out;
            out.self = Self{worldSession_, selfState->runtimeId, selfState->position};
            out.actors = backend_->collectTargets(*selfState, maxRange_);
            return out;
        }
        bool attack(const Self& expectedSelf, std::uint64_t target) override {
            if (!backend_ || !onGameThread() || expectedSelf.session != worldSession_ || expectedSelf.id == 0)
                return false;
            return backend_->attackTarget(expectedSelf.id, target);
        }
        void swing(const Self& expectedSelf) override {
            if (!backend_ || !onGameThread() || expectedSelf.session != worldSession_ || expectedSelf.id == 0)
                return;
            backend_->swingArm(expectedSelf.id);
        }
    };

    std::atomic<bool> desiredFly_{false};
    std::atomic<bool> desiredAura_{false};
    std::atomic<bool> attackMobs_{true};
    std::atomic<bool> multiTarget_{false};
    std::atomic<bool> checkHurtTime_{false};

    std::atomic<bool> inWorld_{false};
    std::atomic<bool> flyActive_{false};
    std::atomic<bool> auraActive_{false};
    std::atomic<std::uint64_t> selfRuntimeId_{0};
    std::atomic<std::uint64_t> authPackets_{0};
    std::atomic<std::uint64_t> motionInjections_{0};
    std::atomic<std::uint64_t> auraTicks_{0};
    std::atomic<std::uint64_t> auraAttacks_{0};
    std::atomic<std::uint64_t> teardownGeneration_{0};

    std::mutex queueMutex_;
    std::deque<PacketEvent> pendingEvents_;
    static constexpr std::size_t kMaxPendingEvents = 128;

    static std::uint64_t currentThreadToken() noexcept {
        static std::atomic<std::uint64_t> next{1};
        thread_local const std::uint64_t token = next.fetch_add(1, std::memory_order_relaxed);
        return token;
    }

    std::atomic<std::uint64_t> gameThreadToken_{0};
    // Owned exclusively by the thread matching gameThreadToken_.
    std::uint64_t observedTeardown_{0};
    std::uint64_t worldSession_{0};
    std::uint64_t seededRuntimeId_{0};
    bool flySessionAttached_{false};
    bool auraAdapterAttached_{false};
    SessionAdapter sessionAdapter_;
    CombatAdapter combatAdapter_;
    std::unique_ptr<FlyPacketRuntime> fly_{makeFlyPacketRuntime()};
    std::unique_ptr<KillAuraRuntime> aura_{makeKillAuraRuntime()};

    void enqueueEvent(const PacketEvent& event) {
        std::lock_guard<std::mutex> lock(queueMutex_);
        if (pendingEvents_.size() >= kMaxPendingEvents) {
            for (auto it = pendingEvents_.begin(); it != pendingEvents_.end(); ++it) {
                if (it->kind == PacketEventKind::AuthInput || it->kind == PacketEventKind::MovePlayer) {
                    pendingEvents_.erase(it);
                    break;
                }
            }
            if (pendingEvents_.size() >= kMaxPendingEvents) pendingEvents_.pop_front();
        }
        pendingEvents_.push_back(event);
    }

    void resetGameThreadRuntimes() {
        sessionAdapter_.bind(nullptr, false);
        combatAdapter_.configure(nullptr, {}, 0, 8.0f);
        fly_ = makeFlyPacketRuntime();
        aura_ = makeKillAuraRuntime();
        flySessionAttached_ = false;
        auraAdapterAttached_ = false;
        seededRuntimeId_ = 0;
        inWorld_.store(false, std::memory_order_relaxed);
        flyActive_.store(false, std::memory_order_relaxed);
        auraActive_.store(false, std::memory_order_relaxed);
        selfRuntimeId_.store(0, std::memory_order_relaxed);
    }

    void dispatchOneOnGameThread(InternalEngineBackend& backend, const PacketEvent& event, const FlySettings& flySettings) {
        if (event.kind == PacketEventKind::Disconnect) {
            resetGameThreadRuntimes();
            return;
        }
        sessionAdapter_.bind(&backend, true);
        if (!flySessionAttached_ && sessionAdapter_.open()) {
            flySessionAttached_ = fly_->attach(sessionAdapter_);
        }
        if (!flySessionAttached_) {
            flyActive_.store(false, std::memory_order_relaxed);
            return;
        }
        if (event.kind == PacketEventKind::StartGame && event.runtimeId != 0) {
            seededRuntimeId_ = event.runtimeId;
            selfRuntimeId_.store(event.runtimeId, std::memory_order_relaxed);
        }
        if (event.kind == PacketEventKind::AuthInput) {
            authPackets_.fetch_add(1, std::memory_order_relaxed);
            const bool wantFly = desiredFly_.load(std::memory_order_relaxed);
            if (fly_->enabled() != wantFly) fly_->setEnabled(wantFly);
        }
        const auto res = fly_->dispatch(event, flySettings);
        if (res.injectedMotion) {
            motionInjections_.fetch_add(1, std::memory_order_relaxed);
        }
        if (event.kind != PacketEventKind::AuthInput) {
            const bool wantFly = desiredFly_.load(std::memory_order_relaxed);
            if (fly_->enabled() != wantFly) fly_->setEnabled(wantFly);
        }
        flyActive_.store(fly_->enabled(), std::memory_order_relaxed);
    }

    void drainPendingOnGameThread(InternalEngineBackend& backend, const FlySettings& flySettings) {
        std::deque<PacketEvent> local;
        {
            std::lock_guard<std::mutex> lock(queueMutex_);
            local.swap(pendingEvents_);
        }
        for (const auto& ev : local) {
            dispatchOneOnGameThread(backend, ev, flySettings);
        }
    }

public:
    void configure(bool flyDesired, bool auraDesired, bool attackMobs, bool multiTarget, bool checkHurtTime) noexcept {
        attackMobs_.store(attackMobs, std::memory_order_relaxed);
        multiTarget_.store(multiTarget, std::memory_order_relaxed);
        checkHurtTime_.store(checkHurtTime, std::memory_order_relaxed);
        desiredFly_.store(flyDesired, std::memory_order_relaxed);
        desiredAura_.store(auraDesired, std::memory_order_relaxed);
        if (!flyDesired) flyActive_.store(false, std::memory_order_relaxed);
        if (!auraDesired) auraActive_.store(false, std::memory_order_relaxed);
    }

    void disarmAll() noexcept {
        desiredFly_.store(false, std::memory_order_relaxed);
        desiredAura_.store(false, std::memory_order_relaxed);
        notifyWorldTeardown();
    }

    void notifyWorldTeardown() noexcept {
        teardownGeneration_.fetch_add(1, std::memory_order_acq_rel);
        inWorld_.store(false, std::memory_order_relaxed);
        flyActive_.store(false, std::memory_order_relaxed);
        auraActive_.store(false, std::memory_order_relaxed);
        selfRuntimeId_.store(0, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(queueMutex_);
        pendingEvents_.clear();
    }

    void pushServerPacket(const PacketEvent& event) {
        if (event.kind == PacketEventKind::Disconnect) {
            notifyWorldTeardown();
            return;
        }
        enqueueEvent(event);
    }

    // Called after the untouched original PlayerAuthInputPacket has already been forwarded.
    void onOutboundAuthInput(InternalEngineBackend& backend, const PacketEvent& authEvent,
                             const FlySettings& flySettings = {}) {
        const auto ownerToken = gameThreadToken_.load(std::memory_order_acquire);
        if (ownerToken != 0 && currentThreadToken() == ownerToken
                && observedTeardown_ == teardownGeneration_.load(std::memory_order_acquire)
                && inWorld_.load(std::memory_order_relaxed)) {
            drainPendingOnGameThread(backend, flySettings);
            dispatchOneOnGameThread(backend, authEvent, flySettings);
            return;
        }
        enqueueEvent(authEvent);
    }

    void onGameThreadTick(InternalEngineBackend& backend,
                          const FlySettings& flySettings = {},
                          AuraSettings auraSettings = {}) {
        const auto nowThread = std::this_thread::get_id();
        const auto nowToken = currentThreadToken();
        const auto currentTeardown = teardownGeneration_.load(std::memory_order_acquire);
        if (gameThreadToken_.load(std::memory_order_relaxed) != nowToken || observedTeardown_ != currentTeardown) {
            observedTeardown_ = currentTeardown;
            resetGameThreadRuntimes();
            gameThreadToken_.store(nowToken, std::memory_order_release);
        }

        const auto liveSelf = backend.readLocalPlayer();
        if (!liveSelf || liveSelf->runtimeId == 0 || !liveSelf->position.finite()) {
            resetGameThreadRuntimes();
            return;
        }

        bool queuedStartGame = false;
        {
            std::lock_guard<std::mutex> lock(queueMutex_);
            for (const auto& ev : pendingEvents_) {
                if (ev.kind == PacketEventKind::StartGame) {
                    queuedStartGame = true;
                    break;
                }
            }
        }
        if (queuedStartGame) {
            drainPendingOnGameThread(backend, flySettings);
        }

        if (seededRuntimeId_ != liveSelf->runtimeId) {
            // New world or mid-world attach after StartGamePacket already elapsed.
            if (worldSession_ == UINT64_MAX) worldSession_ = 1;
            else ++worldSession_;
            sessionAdapter_.bind(&backend, true);
            if (!flySessionAttached_ && sessionAdapter_.open()) {
                flySessionAttached_ = fly_->attach(sessionAdapter_);
            }
            if (flySessionAttached_) {
                PacketEvent seed;
                seed.kind = PacketEventKind::StartGame;
                seed.direction = Direction::ServerToClient;
                seed.runtimeId = liveSelf->runtimeId;
                seed.uniqueId = liveSelf->uniqueId;
                seed.position = liveSelf->position;
                seed.yaw = liveSelf->yaw;
                seed.pitch = liveSelf->pitch;
                seed.dimension = liveSelf->dimension;
                fly_->dispatch(seed, flySettings);
                seededRuntimeId_ = liveSelf->runtimeId;
            }
            auraAdapterAttached_ = false;
        } else if (!flySessionAttached_ && backend.canDeliverClientMotion()) {
            sessionAdapter_.bind(&backend, true);
            if (fly_->attach(sessionAdapter_)) {
                flySessionAttached_ = true;
                PacketEvent seed;
                seed.kind = PacketEventKind::StartGame;
                seed.direction = Direction::ServerToClient;
                seed.runtimeId = liveSelf->runtimeId;
                seed.uniqueId = liveSelf->uniqueId;
                seed.position = liveSelf->position;
                seed.yaw = liveSelf->yaw;
                seed.pitch = liveSelf->pitch;
                seed.dimension = liveSelf->dimension;
                fly_->dispatch(seed, flySettings);
            }
        }

        inWorld_.store(true, std::memory_order_relaxed);
        selfRuntimeId_.store(liveSelf->runtimeId, std::memory_order_relaxed);

        drainPendingOnGameThread(backend, flySettings);
        if (flySessionAttached_) {
            const bool wantFly = desiredFly_.load(std::memory_order_relaxed);
            if (fly_->enabled() != wantFly) fly_->setEnabled(wantFly);
            flyActive_.store(fly_->enabled(), std::memory_order_relaxed);
        } else {
            flyActive_.store(false, std::memory_order_relaxed);
        }

        // Native-only KillAura execution path (zero packet/proxy involvement).
        auraSettings.attackMobs = attackMobs_.load(std::memory_order_relaxed);
        auraSettings.multi = multiTarget_.load(std::memory_order_relaxed);
        auraSettings.checkHurtTime = checkHurtTime_.load(std::memory_order_relaxed);
        const float maxRange = std::max(auraSettings.targetRange, auraSettings.wallRange) + 1.0f;
        combatAdapter_.configure(&backend, nowThread, worldSession_ == 0 ? ++worldSession_ : worldSession_, maxRange);
        if (!auraAdapterAttached_) {
            auraAdapterAttached_ = aura_->attach(combatAdapter_);
        }
        if (auraAdapterAttached_ && liveSelf->alive) {
            const bool wantAura = desiredAura_.load(std::memory_order_relaxed);
            if (aura_->enabled() != wantAura) aura_->setEnabled(wantAura);
            if (aura_->enabled()) {
                auraTicks_.fetch_add(1, std::memory_order_relaxed);
                const unsigned hits = aura_->tick(auraSettings);
                if (hits > 0) auraAttacks_.fetch_add(hits, std::memory_order_relaxed);
            }
            auraActive_.store(aura_->enabled(), std::memory_order_relaxed);
        } else {
            if (auraAdapterAttached_) aura_->setEnabled(false);
            auraActive_.store(false, std::memory_order_relaxed);
        }
    }

    CoordinatorSnapshot snapshot() const noexcept {
        CoordinatorSnapshot out;
        out.inWorld = inWorld_.load(std::memory_order_relaxed);
        out.flyDesired = desiredFly_.load(std::memory_order_relaxed);
        out.flyActive = flyActive_.load(std::memory_order_relaxed);
        out.auraDesired = desiredAura_.load(std::memory_order_relaxed);
        out.auraActive = auraActive_.load(std::memory_order_relaxed);
        out.attackMobs = attackMobs_.load(std::memory_order_relaxed);
        out.multiTarget = multiTarget_.load(std::memory_order_relaxed);
        out.checkHurtTime = checkHurtTime_.load(std::memory_order_relaxed);
        out.selfRuntimeId = selfRuntimeId_.load(std::memory_order_relaxed);
        out.authPackets = authPackets_.load(std::memory_order_relaxed);
        out.motionInjections = motionInjections_.load(std::memory_order_relaxed);
        out.auraTicks = auraTicks_.load(std::memory_order_relaxed);
        out.auraAttacks = auraAttacks_.load(std::memory_order_relaxed);
        return out;
    }
};

} // namespace clientbase::modules
