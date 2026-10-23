#pragma once
#include "self_tracker.h"
#include <memory>
#include <string_view>
#include <thread>

namespace clientbase::modules {
inline constexpr std::string_view supportedEngineBuild = "afa62bd3385161f1facfe36aad58ac63998f46d2";
enum class ModuleId { MotionFly, KillAura };
struct ModuleRegistration { ModuleId id; std::string_view name, category; };
inline constexpr ModuleRegistration registrations[] = {
    {ModuleId::MotionFly,"MotionFly","Movement"}, {ModuleId::KillAura,"KillAura","Combat"}
};

// A real session adapter must establish codec/connection compatibility before binding.
// No sockets/encoders are fabricated here. Injection is deliberately Fly-only.
class PacketSession {
public:
    virtual ~PacketSession() = default;
    virtual bool compatible() const noexcept = 0;
    virtual bool open() const noexcept = 0;
    virtual bool clientBoundMotion(std::uint64_t runtimeId, const Vec3& motion) = 0;
};
enum class PacketEventKind { Other, StartGame, AuthInput, MovePlayer, Respawn, ChangeDimension, Disconnect };
struct PacketEvent {
    PacketEventKind kind{PacketEventKind::Other};
    Direction direction{Direction::ServerToClient};
    std::uint64_t runtimeId{};
    std::int64_t uniqueId{};
    Vec3 position;
    float yaw{}, pitch{};
    int dimension{};
    bool respawnPositionReady{};
    FlyInput input;
};
struct DispatchResult {
    bool forwardOriginal{true}; // No packet is cancelled or mutated by this event path.
    bool injectedMotion{};
    FlyStatus flyStatus{FlyStatus::Ignored};
};

// Session-executor-owned decoded event bus, not an on-wire RakNet/Bedrock codec.
// The caller retains responsibility for forwarding the ORIGINAL packet exactly once.
// Injected client-bound motion must not be fed back as original client AuthInput.
class FlyPacketRuntime {
    SelfTracker tracker_;
    MotionFlyPolicy fly_;
    PacketSession* session_{}; // non-owning; detach BEFORE the session is destroyed
    std::thread::id owner_;
    bool enabled_{};
    void stopFly() { fly_.disable(); enabled_=false; }
    bool onOwner() const { return owner_==std::this_thread::get_id(); }
public:
    bool attach(PacketSession& session) {
        if(session_ && !onOwner())return false;
        detach();
        if(!session.compatible() || !session.open())return false;
        owner_=std::this_thread::get_id();session_=&session;return true;
    }
    void detach() {
        // All mutation is executor-owned; wrong-thread requests cannot touch the session.
        if(session_ && !onOwner())return;
        stopFly();tracker_.reset();session_=nullptr;owner_={};
    }
    bool setEnabled(bool value) {
        if(!onOwner() || !session_)return false;
        if(!value){stopFly();return true;}
        const auto self=tracker_.snapshot();
        if(!session_->compatible() || !session_->open() || !self)return false;
        if(enabled_)return true;
        enabled_=fly_.enable(self->self);return enabled_;
    }
    bool enabled() const { return onOwner() && enabled_; }
    DispatchResult dispatch(const PacketEvent& event, const FlySettings& settings) {
        DispatchResult result;
        if(!onOwner() || !session_)return result;
        if(!session_->open() || !session_->compatible()){detach();return result;}
        switch(event.kind) {
        case PacketEventKind::Disconnect: detach();return result;
        case PacketEventKind::StartGame:
            if(tracker_.startGame(event.direction,event.runtimeId,event.uniqueId,event.position,event.yaw,event.pitch,event.dimension))stopFly();
            return result;
        case PacketEventKind::MovePlayer:
            tracker_.movePlayer(event.direction,event.runtimeId,event.position,event.yaw,event.pitch);return result;
        case PacketEventKind::Respawn:
            if(event.respawnPositionReady && tracker_.respawn(event.direction,event.position))stopFly();
            return result;
        case PacketEventKind::ChangeDimension:
            if(tracker_.changeDimension(event.direction,event.dimension,event.position))stopFly();
            return result;
        case PacketEventKind::AuthInput: break;
        case PacketEventKind::Other: return result;
        }
        // Tracking first: never compute motion from a stale snapshot after a rejected update.
        if(!tracker_.authInput(event.direction,event.position,event.yaw,event.pitch))return result;
        if(!enabled_)return result;
        auto input=event.input;input.kind=PacketKind::PlayerAuthInput;
        input.direction=event.direction;input.yawDegrees=event.yaw;
        const auto snapshot=tracker_.snapshot();
        const auto motion=fly_.onPacket(snapshot->self,input,settings);
        result.flyStatus=motion.status;
        if(motion.status!=FlyStatus::EmitClientMotion)return result;
        try { result.injectedMotion=session_->clientBoundMotion(motion.runtimeId,motion.motion); }
        catch(...) { result.injectedMotion=false; }
        if(!result.injectedMotion){stopFly();result.flyStatus=FlyStatus::Unavailable;}
        return result;
    }
};

struct CombatFrame { Self self;std::vector<Target> actors; };
class NativeCombatAdapter {
public:
    virtual ~NativeCombatAdapter() = default;
    virtual std::string_view engineBuildId() const noexcept = 0;
    virtual bool onGameThread() const noexcept = 0;
    virtual std::optional<CombatFrame> frame() = 0;
    // Implement using verified native game attack/swing APIs, NOT a proxy packet sender.
    // Re-resolve runtime IDs and validate alive/attackability at point of attack.
    virtual bool attack(const Self& expectedSelf, std::uint64_t target) = 0;
    virtual void swing(const Self& expectedSelf) = 0;
};
class KillAuraRuntime {
    NativeCombatAdapter* adapter_{};
    KillAuraPolicy policy_;
    bool enabled_{};
    std::uint64_t session_{},self_{};
public:
    bool attach(NativeCombatAdapter& adapter) {
        if((adapter_ && !adapter_->onGameThread()) || !adapter.onGameThread())return false;
        detach();
        if(adapter.engineBuildId()!=supportedEngineBuild)return false;
        adapter_=&adapter;return true;
    }
    void detach(){adapter_=nullptr;enabled_=false;session_=self_=0;policy_.reset();}
    bool setEnabled(bool value) {
        if(!adapter_ || !adapter_->onGameThread())return false;
        if(!value){enabled_=false;policy_.reset();session_=self_=0;return true;}
        enabled_=false;policy_.reset();
        if(adapter_->engineBuildId()!=supportedEngineBuild)return false;
        try {
            auto* attaching=adapter_;
            auto frame=attaching->frame();
            if(adapter_!=attaching || !frame || !frame->self.valid())return false;
            session_=frame->self.session;self_=frame->self.id;policy_.reset();enabled_=true;return true;
        } catch(...) {detach();return false;}
    }
    bool enabled()const{return enabled_;}
    unsigned tick(const AuraSettings& settings) {
        unsigned attacks=0;
        try {
        if(!enabled_ || !adapter_ || !adapter_->onGameThread())return 0;
        if(adapter_->engineBuildId()!=supportedEngineBuild){detach();return 0;}
        const auto frame=adapter_->frame();
        if(!frame || !frame->self.valid() || frame->self.session!=session_ || frame->self.id!=self_) {
            enabled_=false;policy_.reset();return 0;
        }
        auto* executing=adapter_;
        const auto plan=policy_.tick(frame->self,frame->actors,settings);
        for(auto target:plan.targets) {
            // Previous attacks/callbacks can change dimension, target validity, or local-player state.
            if(!enabled_ || adapter_!=executing)break;
            const auto fresh=executing->frame();
            if(!enabled_ || adapter_!=executing)break;
            if(!fresh || !fresh->self.valid() || fresh->self.session!=session_ || fresh->self.id!=self_) {
                enabled_=false;policy_.reset();break;
            }
            KillAuraPolicy validate;auto immediate=settings;immediate.delayTicks=0;immediate.multi=true;
            const auto current=validate.tick(fresh->self,fresh->actors,immediate);
            if(std::find(current.targets.begin(),current.targets.end(),target)==current.targets.end())continue;
            if(executing->attack(fresh->self,target)){
                ++attacks;
                if(!enabled_ || adapter_!=executing)break;
                const auto after=executing->frame();
                if(!enabled_ || adapter_!=executing)break;
                if(!after || after->self.session!=session_ || after->self.id!=self_){
                    enabled_=false;policy_.reset();break;
                }
                executing->swing(after->self);
            }
        }
        return attacks;
        } catch(...) {
            enabled_=false;policy_.reset();return attacks;
        }
    }
};
// Concrete factories are compiled by the Android NDK; callers must supply live adapters.
std::unique_ptr<FlyPacketRuntime> makeFlyPacketRuntime();
std::unique_ptr<KillAuraRuntime> makeKillAuraRuntime();
} // namespace clientbase::modules
