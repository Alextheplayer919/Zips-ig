#pragma once
#include <cstdint>
#include <string>
namespace clientbase {
struct LoadedIdentity { bool confirmed{};uintptr_t bias{};std::string buildId;unsigned machine{}; };
LoadedIdentity loadedIdentity(const std::string& path);
}
