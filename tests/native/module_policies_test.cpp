#include "module_policies.h"
#include <cassert>
#include <limits>
using namespace clientbase::modules;
static bool near(float a, float b) { return std::abs(a-b)<0.0001f; }
int main() {
    Self self{1,10,{0,0,0}};
    Target nearPlayer{12,{0,0,2},true,true,false,true,true,0};
    Target farPlayer{13,{0,0,6},true,true,false,true,true,0};
    Target mob{14,{0,0,1},false,true,false,true,true,0};
    KillAuraPolicy aura; AuraSettings config;
    assert(aura.tick(self,{farPlayer,nearPlayer,mob},config).targets.empty());
    assert(aura.tick(self,{farPlayer,nearPlayer,mob},config).targets.empty());
    assert(aura.tick(self,{farPlayer,nearPlayer,mob},config).targets==std::vector<std::uint64_t>{12});
    config.delayTicks=0;config.multi=true;
    assert((aura.tick(self,{farPlayer,nearPlayer,nearPlayer,mob},config).targets==std::vector<std::uint64_t>{12,13}));
    config.attackMobs=true;
    assert((aura.tick(self,{farPlayer,nearPlayer,mob},config).targets==std::vector<std::uint64_t>{14,12,13}));
    config.multi=false; config.checkHurtTime=true; mob.hurtTicks=2;
    assert(aura.tick(self,{mob,nearPlayer},config).targets.empty());
    config.multi=true;
    assert(aura.tick(self,{mob,nearPlayer},config).targets==std::vector<std::uint64_t>{12});
    farPlayer.visible=false;nearPlayer.friendEntity=true;mob.alive=false;
    assert(aura.tick(self,{farPlayer,nearPlayer,mob},config).targets.empty());
    farPlayer.position.z=4;
    assert(aura.tick(self,{farPlayer},config).targets==std::vector<std::uint64_t>{13});
    farPlayer.position.z=5;assert(aura.tick(self,{farPlayer},config).targets.empty());
    farPlayer.position.z=std::numeric_limits<float>::quiet_NaN();
    assert(aura.tick(self,{farPlayer},config).targets.empty());
    config.delayTicks=2;nearPlayer.friendEntity=false;
    aura.tick(self,{nearPlayer},config);aura.tick(self,{nearPlayer},config);
    Self nextWorld=self;nextWorld.session=2;
    assert(aura.tick(nextWorld,{nearPlayer},config).targets.empty());
    aura.reset();assert(aura.tick(self,{nearPlayer},config).targets.empty());
    config.targetRange=std::numeric_limits<float>::infinity();
    assert(aura.tick(self,{nearPlayer},config).targets.empty());

    MotionFlyPolicy fly; FlySettings settings; FlyDependencies dependencies;
    FlyInput input;input.kind=PacketKind::PlayerAuthInput;input.direction=Direction::ClientToServer;input.forward=1;
    assert(fly.onPacket(self,input,settings,dependencies).status==FlyStatus::Ignored);
    assert(fly.enable(self));
    assert(fly.onPacket(self,input,settings,dependencies).status==FlyStatus::Unavailable);
    int collisionCalls=0;
    // Test fixture only: production must supply real collision data; not a collision implementation.
    dependencies.collisionClamp=[&](const Self&,const Vec3& motion)->std::optional<Vec3>{++collisionCalls;return motion;};
    auto other=input;other.kind=PacketKind::Other;
    assert(fly.onPacket(self,other,settings,dependencies).status==FlyStatus::Ignored);
    other=input;other.direction=Direction::ServerToClient;
    assert(fly.onPacket(self,other,settings,dependencies).status==FlyStatus::Ignored);assert(collisionCalls==0);
    auto result=fly.onPacket(self,input,settings,dependencies);
    assert(result.status==FlyStatus::EmitClientMotion && result.runtimeId==10 && result.session==1);
    assert(near(result.motion.z,2.3f) && near(result.motion.x,0) && near(result.motion.y,-0.02f));
    input.yawDegrees=90;result=fly.onPacket(self,input,settings,dependencies);
    assert(near(result.motion.x,-2.3f) && near(result.motion.z,0));
    input.jumping=true;result=fly.onPacket(self,input,settings,dependencies);
    assert(near(result.motion.x,-2.3f*0.55f) && near(result.motion.y,0.99f));
    input.jumping=false;input.sneaking=true;result=fly.onPacket(self,input,settings,dependencies);
    assert(near(result.motion.y,-2.32f));input.sneaking=false;
    settings.horizontalBps=100;input.yawDegrees=0;input.strafe=1;
    result=fly.onPacket(self,input,settings,dependencies);
    assert(near(std::hypot(result.motion.x,result.motion.z),float(std::sqrt(5.99))));
    Self corrected=self;corrected.position.x=5;
    assert(fly.onPacket(corrected,input,settings,dependencies).status==FlyStatus::CorrectionSkipped);
    assert(fly.onPacket(corrected,input,settings,dependencies).status==FlyStatus::EmitClientMotion);
    settings.adaptive=true;
    assert(fly.onPacket(corrected,input,settings,dependencies).status==FlyStatus::Unavailable);
    dependencies.adaptiveGovernor=[](float,float,float)->std::optional<GovernedSpeed>{return GovernedSpeed{0.5f,-0.1f};};
    result=fly.onPacket(corrected,input,settings,dependencies);
    assert(near(std::hypot(result.motion.x,result.motion.z),0.5f) && near(result.motion.y,-0.1f));
    dependencies.collisionClamp=[](const Self&,const Vec3&)->std::optional<Vec3>{return std::nullopt;};
    assert(fly.onPacket(corrected,input,settings,dependencies).status==FlyStatus::Unavailable);
    assert(fly.onPacket(nextWorld,input,settings,dependencies).status==FlyStatus::Unavailable);
    assert(fly.onPacket(self,input,settings,dependencies).status==FlyStatus::Ignored); // world change disables
    assert(fly.enable(self));input.forward=std::numeric_limits<float>::infinity();
    assert(fly.onPacket(self,input,settings,dependencies).status==FlyStatus::Unavailable);
    fly.disable();assert(fly.onPacket(self,input,settings,dependencies).status==FlyStatus::Ignored);
}
