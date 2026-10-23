#include "module_runtime.h"
#include <cassert>
#include <limits>
#include <stdexcept>
using namespace clientbase::modules;
// Fixtures are stand-ins for adapters in tests ONLY, not a fake production game/transport.
class Session final:public PacketSession {
public:
    bool live=true,verified=true,accept=true,throws=false;int sent=0;Vec3 last;std::uint64_t id=0;
    bool compatible()const noexcept override{return verified;}
    bool open()const noexcept override{return live;}
    bool clientBoundMotion(std::uint64_t rid,const Vec3& motion)override {
        if(throws)throw std::runtime_error("fixture send failure");
        if(!accept)return false;
        ++sent;last=motion;id=rid;return true;
    }
};
class Combat final:public NativeCombatAdapter {
public:
    std::string_view build=supportedEngineBuild;bool thread=true;int attacks=0,swings=0;bool disappear=false,failAttack=false;KillAuraRuntime* stopDuringAttack=nullptr;
    CombatFrame state{{1,100,{0,0,0}},{{200,{0,0,1},true,true,false,true,true,0},{300,{0,0,2},true,true,false,true,true,0}}};
    std::string_view engineBuildId()const noexcept override{return build;}
    bool onGameThread()const noexcept override{return thread;}
    std::optional<CombatFrame> frame()override{return state;}
    bool attack(const Self& expected,std::uint64_t)override {
        assert(expected.session==state.self.session);
        if(failAttack)throw std::runtime_error("fixture attack error");
        ++attacks;
        if(stopDuringAttack)stopDuringAttack->detach();
        if(disappear)state.actors.clear();
        return true;
    }
    void swing(const Self&)override{++swings;}
};
int main() {
    assert(registrations[0].category=="Movement" && registrations[1].category=="Combat");
    Session session;FlyPacketRuntime bus;FlySettings settings;
    assert(!bus.setEnabled(true));assert(bus.attach(session));assert(!bus.setEnabled(true));
    PacketEvent event;event.kind=PacketEventKind::StartGame;event.runtimeId=10;
    assert(bus.dispatch(event,settings).forwardOriginal);assert(bus.setEnabled(true));
    event.kind=PacketEventKind::AuthInput;event.direction=Direction::ClientToServer;
    event.position={1,0,0};event.input.forward=1;event.yaw=90;
    auto result=bus.dispatch(event,settings);
    assert(result.forwardOriginal && result.injectedMotion && session.sent==1 && session.id==10);
    assert(std::abs(session.last.x+2.3f)<0.0001f);
    event.kind=PacketEventKind::Other;assert(!bus.dispatch(event,settings).injectedMotion);
    event.kind=PacketEventKind::AuthInput;event.direction=Direction::ServerToClient;
    assert(!bus.dispatch(event,settings).injectedMotion);assert(session.sent==1);
    event.direction=Direction::ClientToServer;event.position.x=std::numeric_limits<float>::quiet_NaN();
    assert(!bus.dispatch(event,settings).injectedMotion);assert(session.sent==1);
    event.position={2,0,0};std::thread wrong([&]{assert(!bus.dispatch(event,settings).injectedMotion);assert(!bus.setEnabled(true));});wrong.join();
    assert(session.sent==1);
    session.accept=false;result=bus.dispatch(event,settings);assert(result.forwardOriginal&&!result.injectedMotion&&!bus.enabled());
    session.accept=true;assert(bus.setEnabled(true));session.throws=true;
    assert(bus.dispatch(event,settings).forwardOriginal);assert(!bus.enabled());session.throws=false;
    assert(bus.setEnabled(true));event.kind=PacketEventKind::ChangeDimension;event.direction=Direction::ServerToClient;
    bus.dispatch(event,settings);assert(!bus.enabled());assert(bus.setEnabled(true));
    event.kind=PacketEventKind::Disconnect;bus.dispatch(event,settings);assert(!bus.setEnabled(true));
    assert(bus.attach(session));assert(!bus.setEnabled(true));
    session.verified=false;assert(bus.dispatch(event,settings).forwardOriginal);assert(!bus.setEnabled(true));
    Combat adapter;KillAuraRuntime aura;AuraSettings auraSettings;auraSettings.delayTicks=0;auraSettings.multi=true;
    assert(!aura.setEnabled(true));adapter.build="wrong";assert(!aura.attach(adapter));
    adapter.build=supportedEngineBuild;assert(aura.attach(adapter));assert(aura.setEnabled(true));
    adapter.thread=false;assert(aura.tick(auraSettings)==0 && adapter.attacks==0);adapter.thread=true;
    assert(aura.tick(auraSettings)==2 && adapter.attacks==2 && adapter.swings==2);
    adapter.state.self.session++;assert(aura.tick(auraSettings)==0 && !aura.enabled());
    assert(aura.setEnabled(true));adapter.disappear=true;
    assert(aura.tick(auraSettings)==1 && adapter.attacks==3); // second target disappeared after first attack
    aura.detach();assert(!aura.enabled() && !aura.setEnabled(true));
    adapter.disappear=false;assert(aura.attach(adapter));assert(aura.setEnabled(true));
    adapter.build="different engine";assert(aura.tick(auraSettings)==0 && !aura.enabled());
    Combat failing;assert(aura.attach(failing));assert(aura.setEnabled(true));failing.failAttack=true;
    assert(aura.tick(auraSettings)==0 && !aura.enabled());
    failing.failAttack=false;failing.stopDuringAttack=&aura;
    assert(aura.setEnabled(true));assert(aura.tick(auraSettings)==1 && !aura.enabled());
    assert(failing.swings==0); // stop during attack must not call another callback

}
