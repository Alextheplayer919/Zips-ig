#include "module_runtime.h"

namespace clientbase::modules {
std::unique_ptr<FlyPacketRuntime> makeFlyPacketRuntime() {
    return std::make_unique<FlyPacketRuntime>();
}
std::unique_ptr<KillAuraRuntime> makeKillAuraRuntime() {
    return std::make_unique<KillAuraRuntime>();
}
} // namespace clientbase::modules
