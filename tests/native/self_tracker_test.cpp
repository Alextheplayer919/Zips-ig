#include "self_tracker.h"
#include <cassert>
#include <limits>
using namespace clientbase::modules;
int main() {
    SelfTracker tracker;
    const auto up=Direction::ClientToServer, down=Direction::ServerToClient;
    assert(!tracker.snapshot());
    assert(!tracker.authInput(up,{1,2,3},0,0));
    assert(!tracker.startGame(up,10,-99,{1,2,3},0,0,0));
    assert(!tracker.startGame(down,0,-99,{1,2,3},0,0,0));
    assert(tracker.startGame(down,10,-99,{1,2,3},90,10,0));
    const auto first=*tracker.snapshot();
    assert(first.self.valid() && first.uniqueId==-99 && first.self.id==10);
    assert(first.source==PositionSource::StartGame && first.yaw==90 && first.pitch==10);
    assert(!tracker.authInput(down,{9,9,9},0,0));
    assert(tracker.authInput(up,{2,4,6},45,-20));
    auto state=*tracker.snapshot();
    assert(state.self.position.y==4 && state.source==PositionSource::AuthInput && state.self.session==first.self.session);
    assert(!tracker.movePlayer(down,999,{9,9,9},0,0));
    assert(tracker.snapshot()->self.position.x==2);
    assert(tracker.movePlayer(down,10,{3,5,7},20,30));
    assert(tracker.snapshot()->self.position.x==3 && tracker.snapshot()->source==PositionSource::MovePlayer);
    assert(tracker.movePlayer(up,10,{4,6,8},20,30));
    const float nan=std::numeric_limits<float>::quiet_NaN();
    assert(!tracker.authInput(up,{nan,1,2},0,0));
    assert(!tracker.movePlayer(up,10,{1,2,3},nan,0));
    assert(tracker.snapshot()->self.position.x==4);
    MotionFlyPolicy fly;FlyInput input;FlySettings settings;
    input.kind=PacketKind::PlayerAuthInput;input.direction=up;input.forward=1;
    assert(fly.enable(tracker.snapshot()->self));
    assert(fly.onPacket(tracker.snapshot()->self,input,settings).status==FlyStatus::EmitClientMotion);
    assert(tracker.movePlayer(down,10,{12,6,8},20,30));
    assert(fly.onPacket(tracker.snapshot()->self,input,settings).status==FlyStatus::CorrectionSkipped);
    assert(fly.onPacket(tracker.snapshot()->self,input,settings).status==FlyStatus::EmitClientMotion);
    assert(!tracker.changeDimension(up,1,{0,10,0}));
    assert(tracker.changeDimension(down,1,{0,10,0}));
    state=*tracker.snapshot();
    assert(state.self.session!=first.self.session && state.dimension==1 && state.source==PositionSource::Dimension);
    assert(fly.onPacket(state.self,input,settings).status==FlyStatus::Unavailable);
    assert(fly.onPacket(state.self,input,settings).status==FlyStatus::Ignored);
    assert(fly.enable(state.self));
    assert(tracker.respawn(down,{2,8,4}));
    const auto respawn=*tracker.snapshot();
    assert(respawn.self.session!=state.self.session && respawn.source==PositionSource::Respawn);
    assert(respawn.self.position.y==8); // no guessed 1.62 conversion
    assert(fly.onPacket(respawn.self,input,settings).status==FlyStatus::Unavailable);
    tracker.reset();assert(!tracker.snapshot());
    assert(tracker.startGame(down,10,-99,{0,0,0},0,0,0));
    assert(tracker.snapshot()->self.session!=respawn.self.session);
    assert(!tracker.startGame(down,12,7,{nan,0,0},0,0,0));
    assert(tracker.snapshot()->self.id==10);
    assert(!tracker.respawn(up,{0,0,0}));
}
