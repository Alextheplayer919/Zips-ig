#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <optional>
#include <unordered_set>
#include <vector>

// Portable policy ports only, NOT an engine SDK, JNI command or packet codec.
// KillAura selection/cadence adapted from Melody (MIT, copyright 2024 tonytranrp).
// MotionFly math is adapted from the user-supplied Rubidium MotionFly.kt attachment.
// See third_party/melody/LICENSE and docs/MODULE_PORTS.md for provenance/limits.
namespace clientbase::modules {
struct Vec3 {
    float x{}, y{}, z{};
    bool finite() const { return std::isfinite(x) && std::isfinite(y) && std::isfinite(z); }
};
inline double distance(const Vec3& a, const Vec3& b) {
    return std::hypot(std::hypot(double(a.x) - b.x, double(a.y) - b.y), double(a.z) - b.z);
}
struct Self {
    std::uint64_t session{}, id{}; // adapter-issued world/session generation, not an address
    Vec3 position;
    bool valid() const { return session != 0 && id != 0 && position.finite(); }
};
struct Target {
    std::uint64_t id{};
    Vec3 position;
    bool player{}, alive{}, friendEntity{}, attackable{}, visible{};
    int hurtTicks{};
};
struct AuraSettings {
    float targetRange{7}, wallRange{5};
    int delayTicks{2};
    bool multi{}, attackMobs{}, checkHurtTime{};
    bool valid() const {
        return std::isfinite(targetRange) && targetRange >= 3 && targetRange <= 12
            && std::isfinite(wallRange) && wallRange >= 0 && wallRange <= 12
            && delayTicks >= 0 && delayTicks <= 20;
    }
};
struct AttackPlan {
    std::uint64_t session{};
    std::vector<std::uint64_t> targets;
};
class KillAuraPolicy {
    unsigned delay_{};
    std::uint64_t session_{}, self_{};
public:
    void reset() { delay_ = 0; session_ = self_ = 0; }
    AttackPlan tick(const Self& self, const std::vector<Target>& actors, const AuraSettings& settings) {
        AttackPlan result;
        if (!self.valid() || !settings.valid()) { reset(); return result; }
        if (self.session != session_ || self.id != self_) {
            reset(); session_ = self.session; self_ = self.id;
        }
        result.session = self.session;
        struct Candidate { const Target* target; double range; };
        std::vector<Candidate> candidates;
        std::unordered_set<std::uint64_t> seen;
        for (const auto& actor : actors) {
            if (!actor.id || actor.id == self.id || !actor.position.finite() || !actor.alive
                || actor.friendEntity || !actor.attackable || (!settings.attackMobs && !actor.player)) continue;
            const double range = distance(self.position, actor.position);
            if (range < (actor.visible ? settings.targetRange : settings.wallRange) && seen.insert(actor.id).second)
                candidates.push_back({&actor, range});
        }
        std::sort(candidates.begin(), candidates.end(), [](const Candidate& a, const Candidate& b) {
            if (a.range != b.range) return a.range < b.range;
            return a.target->id < b.target->id;
        });
        if (candidates.empty()) return result;
        // Preserve Melody cadence: delay=2 attacks on the third eligible tick.
        if (delay_ < unsigned(settings.delayTicks)) { ++delay_; return result; }
        delay_ = 0;
        const std::size_t count = settings.multi ? candidates.size() : 1;
        for (std::size_t i = 0; i < count; ++i) {
            const auto& target = *candidates[i].target;
            if (!settings.checkHurtTime || target.hurtTicks <= 0) result.targets.push_back(target.id);
        }
        return result; // Native attack intents only. No send/edit/rotation/equipment packets.
    }
};

// Semantic packet tags, NOT numeric Bedrock IDs/layouts. A validated codec must map them.
enum class PacketKind { Other, PlayerAuthInput };
enum class Direction { ClientToServer, ServerToClient };
struct FlyInput {
    PacketKind kind{PacketKind::Other};
    Direction direction{Direction::ServerToClient};
    float strafe{}, forward{}, yawDegrees{};
    bool wantUp{}, jumping{}, wantDown{}, sneaking{};
};
struct FlySettings {
    float horizontalBps{46}, upBps{19.8f}, downBps{46}, glide{-0.02f};
    float upHorizontalFactor{0.55f}, downHorizontalFactor{0.55f};
    bool adaptive{};
    bool valid() const {
        auto between = [](float v, float low, float high) { return std::isfinite(v) && v >= low && v <= high; };
        return between(horizontalBps,1,100) && between(upBps,1,60) && between(downBps,1,60)
            && between(glide,-0.3f,0) && between(upHorizontalFactor,0.1f,1)
            && between(downHorizontalFactor,0.1f,1);
    }
};
struct GovernedSpeed { float horizontal{}, vertical{}; };
struct FlyDependencies {
    // Must be supplied by real world/proxy state. No invented collision/governor fallback.
    std::function<std::optional<Vec3>(const Self&, const Vec3&)> collisionClamp;
    std::function<std::optional<GovernedSpeed>(float, float, float)> adaptiveGovernor;
};
enum class FlyStatus { Ignored, Unavailable, CorrectionSkipped, EmitClientMotion };
struct FlyResult {
    FlyStatus status{FlyStatus::Ignored};
    std::uint64_t session{}, runtimeId{};
    Vec3 motion;
};
class MotionFlyPolicy {
    bool enabled_{};
    Self last_;
public:
    bool enable(const Self& self) { disable(); if (!self.valid()) return false; last_ = self; enabled_ = true; return true; }
    void disable() { enabled_ = false; last_ = {}; }
    FlyResult onPacket(const Self& self, const FlyInput& input, const FlySettings& settings,
                       const FlyDependencies& dependencies) {
        if (!enabled_ || input.kind != PacketKind::PlayerAuthInput || input.direction != Direction::ClientToServer)
            return {};
        FlyResult result; result.status = FlyStatus::Unavailable;
        if (!self.valid() || self.session != last_.session || self.id != last_.id) { disable(); return result; }
        if (!settings.valid() || !std::isfinite(input.strafe) || !std::isfinite(input.forward)
            || !std::isfinite(input.yawDegrees)) return result;
        if (distance(self.position, last_.position) > 4.5) {
            last_ = self; result.status = FlyStatus::CorrectionSkipped; return result;
        }
        const bool up = input.wantUp || input.jumping, down = input.wantDown || input.sneaking;
        if (!up && !down && input.strafe == 0 && input.forward == 0 && settings.glide == 0) {
            last_ = self; return {};
        }
        if (!dependencies.collisionClamp || (settings.adaptive && !dependencies.adaptiveGovernor)) return result;
        float horizontal = std::min(settings.horizontalBps / 20.f, float(std::sqrt(5.99)));
        if (up) horizontal *= settings.upHorizontalFactor;
        if (down) horizontal *= settings.downHorizontalFactor;
        float vertical = up ? settings.upBps / 20.f : down ? -settings.downBps / 20.f + settings.glide : settings.glide;
        if (settings.adaptive) {
            auto governed = dependencies.adaptiveGovernor(horizontal, vertical, settings.glide);
            if (!governed || !std::isfinite(governed->horizontal) || governed->horizontal < 0
                || !std::isfinite(governed->vertical)) return result;
            horizontal = governed->horizontal; vertical = governed->vertical;
        }
        const double yaw = std::remainder(double(input.yawDegrees), 360.0) * 3.14159265358979323846 / 180.0;
        double x = double(input.strafe) * std::cos(yaw) - double(input.forward) * std::sin(yaw);
        double z = double(input.forward) * std::cos(yaw) + double(input.strafe) * std::sin(yaw);
        const double length = std::hypot(x,z);
        if (length > 0.001) { x = x / length * horizontal; z = z / length * horizontal; }
        else { x = z = 0; }
        auto clamped = dependencies.collisionClamp(self, {float(x),vertical,float(z)});
        if (!clamped || !clamped->finite()) return result;
        last_ = self;
        result.status = FlyStatus::EmitClientMotion; result.session = self.session;
        result.runtimeId = self.id; result.motion = *clamped;
        return result; // Intent for client-bound SetEntityMotion; no socket/JNI/network side effect here.
    }
};
} // namespace clientbase::modules
