#pragma once
#include "module_policies.h"
#include <limits>
#include <optional>

namespace clientbase::modules {
// Input channel tags only: do not assume an eye/feet conversion or a fixed Y offset.
enum class PositionSource { StartGame, AuthInput, MovePlayer, Respawn, Dimension };
struct TrackedSelf {
    Self self;
    std::int64_t uniqueId{};
    float yaw{}, pitch{};
    int dimension{};
    PositionSource source{PositionSource::StartGame};
};

// Minimal self-tracking adaptation of the user-supplied EntityTracker.kt.
// Call on one packet/session executor; snapshot is a coherent value, not independent volatile fields.
// Keep this owner across connections; reset on disconnect. Transport teardown must disable Fly.
// Semantic decoded input only: this is NOT a wire codec or a packet event bus.
class SelfTracker {
    std::uint64_t generation_{};
    std::optional<TrackedSelf> state_;
    bool newGeneration() {
        if (generation_ == std::numeric_limits<std::uint64_t>::max()) { reset(); return false; }
        ++generation_;
        return true;
    }
    static bool validPose(const Vec3& position, float yaw, float pitch) {
        return position.finite() && std::isfinite(yaw) && std::isfinite(pitch);
    }
public:
    void reset() { state_.reset(); } // Do not reuse the old generation after reconnect.
    std::optional<TrackedSelf> snapshot() const { return state_; }
    bool startGame(Direction direction, std::uint64_t runtimeId, std::int64_t uniqueId,
                   const Vec3& position, float yaw, float pitch, int dimension) {
        if (direction != Direction::ServerToClient || !runtimeId || !validPose(position,yaw,pitch)) return false;
        if (!newGeneration()) return false;
        state_ = TrackedSelf{{generation_,runtimeId,position},uniqueId,yaw,pitch,dimension,PositionSource::StartGame};
        return true;
    }
    bool authInput(Direction direction, const Vec3& position, float yaw, float pitch) {
        if (!state_ || direction != Direction::ClientToServer || !validPose(position,yaw,pitch)) return false;
        state_->self.position=position;state_->yaw=yaw;state_->pitch=pitch;state_->source=PositionSource::AuthInput;
        return true;
    }
    bool movePlayer(Direction direction, std::uint64_t runtimeId, const Vec3& position, float yaw, float pitch) {
        if (!state_ || runtimeId != state_->self.id || !validPose(position,yaw,pitch)) return false;
        if (direction != Direction::ClientToServer && direction != Direction::ServerToClient) return false;
        // Unlike the uploaded handler, also retain authoritative server self corrections.
        state_->self.position=position;state_->yaw=yaw;state_->pitch=pitch;state_->source=PositionSource::MovePlayer;
        return true;
    }
    bool respawn(Direction direction, const Vec3& position) {
        // The codec/session adapter must select the relevant respawn state before calling.
        if (!state_ || direction != Direction::ServerToClient || !position.finite()) return false;
        if (!newGeneration()) return false;
        state_->self.session=generation_;state_->self.position=position;state_->source=PositionSource::Respawn;
        return true;
    }
    bool changeDimension(Direction direction, int dimension, const Vec3& position) {
        if (!state_ || direction != Direction::ServerToClient || !position.finite()) return false;
        if (!newGeneration()) return false;
        state_->self.session=generation_;state_->self.position=position;
        state_->dimension=dimension;state_->source=PositionSource::Dimension;
        return true;
    }
};
} // namespace clientbase::modules
