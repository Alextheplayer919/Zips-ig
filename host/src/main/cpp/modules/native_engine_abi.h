#pragma once
#include "module_runtime.h"
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string_view>

namespace clientbase::modules::abi {

// Pin: Minecraft Bedrock Android ARM64 1.26.50.4 (versionCode 972605004).
// All addresses below are ELF virtual addresses resolved at runtime via dlpi_addr + elf_va.
inline constexpr std::string_view kSupportedLibrarySha256 =
    "fac2be5b17ef79419ebd66fe4f9e362a48d7b2067d5e1f649e65c36edf2927e1";
inline constexpr std::string_view kSupportedBuildId =
    "afa62bd3385161f1facfe36aad58ac63998f46d2";

struct VtableSpec {
    std::uintptr_t addressPointVa; // vtable + 0x10 (what object[0] points to)
    std::string_view rttiTypeName;
};

struct MethodSpec {
    std::uintptr_t vtableAddressPointVa;
    std::size_t slotIndex;
    std::uintptr_t expectedFnVa;
};

struct DirectFnSpec {
    std::uintptr_t fnVa;
    std::uint32_t expectedInsn0;
    std::uint32_t expectedInsn1;
};

// --- Verified vtable address points ---
inline constexpr VtableSpec kLoopbackSenderVtable{0x12e2f288, "20LoopbackPacketSender"};
inline constexpr VtableSpec kLcnhVtable{0x12d248c8, "26LegacyClientNetworkHandler"};
inline constexpr VtableSpec kGameModeVtable{0x13016718, "8GameMode"};
inline constexpr VtableSpec kSurvivalModeVtable{0x13016848, "12SurvivalMode"};
inline constexpr VtableSpec kLocalPlayerVtable{0x12d08918, "11LocalPlayer"};
inline constexpr VtableSpec kRemotePlayerVtable{0x12d09120, "12RemotePlayer"};
inline constexpr VtableSpec kPlayerAuthInputVtable{0x12e86ab0, "21PlayerAuthInputPacket"};

// --- Verified virtual method slots ---
// LoopbackPacketSender::send (vt+0x18 = 0xc68fa2c) dispatches client instances ([self+0x28]==0U)
// through std::visit (0x12e2fec8[0] -> 0xc6a4fc0) directly into slot [5] (sendToServer).
inline constexpr MethodSpec kSenderSendToServer{0x12e2f288, 5, 0xc68fd38};

inline constexpr MethodSpec kLcnhDtorD1{0x12d248c8, 0, 0xad6a638};
inline constexpr MethodSpec kLcnhDtorD0{0x12d248c8, 1, 0xad6aa14};
inline constexpr MethodSpec kLcnhDisconnect{0x12d248c8, 19, 0xad6bca8};
inline constexpr MethodSpec kLcnhStartGame{0x12d248c8, 37, 0xad6d300};
inline constexpr MethodSpec kLcnhMovePlayer{0x12d248c8, 47, 0xad749a0};
inline constexpr MethodSpec kLcnhChangeDimension{0x12d248c8, 99, 0xad7c52c};
inline constexpr MethodSpec kLcnhRespawn{0x12d248c8, 170, 0x9ed3754};

// NOTE: GameMode::tick and SurvivalMode::tick have 0 callers in 1.26.50.4.
// Client world ticks run through LocalPlayer::normalTick (slot [25], vt+0xc8 = 0xaac7cf8), while
// ~LocalPlayer D1/D0 sit at slots [8]/[9] (after Actor's 8 pre-destructor virtuals).
inline constexpr MethodSpec kLocalPlayerDtorD1{0x12d08918, 8, 0xaac61a0};
inline constexpr MethodSpec kLocalPlayerDtorD0{0x12d08918, 9, 0xaac658c};
inline constexpr MethodSpec kLocalPlayerLerpMotion{0x12d08918, 23, 0xf578d1c};
inline constexpr MethodSpec kLocalPlayerNormalTick{0x12d08918, 25, 0xaac7cf8};
inline constexpr MethodSpec kLocalPlayerIsAlive{0x12d08918, 47, 0xf285c14};
inline constexpr MethodSpec kLocalPlayerSwing{0x12d08918, 110, 0xaaca6cc};

inline constexpr MethodSpec kGameModeAttack{0x13016718, 16, 0xf89f570};
inline constexpr MethodSpec kSurvivalModeAttack{0x13016848, 16, 0xf8a3adc};

// --- Verified direct helper functions (first two ARM64 little-endian instructions) ---
inline constexpr DirectFnSpec kLocalPlayerNormalTickFn{0xaac7cf8, 0xfc190fe8U, 0xa9017bfdU};
inline constexpr DirectFnSpec kActorGetRuntimeId{0xf566b90, 0xa9bf7bfdU, 0x910003fdU};
inline constexpr DirectFnSpec kActorGetUniqueId{0xf566e30, 0xa9be7bfdU, 0xf9000bf3U};
inline constexpr DirectFnSpec kActorGetPosition{0xf563e6c, 0xf9410400U, 0xd65f03c0U};
inline constexpr DirectFnSpec kActorCanSee{0xf5841c0, 0xd10183ffU, 0xa9027bfdU};
inline constexpr DirectFnSpec kActorIsClientSide{0xf56aa70, 0xf940e800U, 0xb4000080U};
inline constexpr DirectFnSpec kPlayerGetDimensionId{0xf9e34f8, 0xa9be7bfdU, 0xf9000bf3U};
inline constexpr std::uintptr_t kEngineOperatorDelete = 0x12911250; // _ZdlPv PLT

// --- Verified binary field offsets in 1.26.50.4 ---
// LoopbackPacketSender: libc++ std::variant<ClientNetworkSystem*, ServerNetworkSystem*> uses a
// 32-bit unsigned __index at +0x28 (0U = ClientNetworkSystem*, 1U = ServerNetworkSystem*),
// followed by 4 bytes of tail padding at +0x2c..0x2f (must be read as uint32_t, never size_t).
inline constexpr std::size_t kSenderVariantIndexOffset = 0x28;

// PlayerAuthInputPacket
inline constexpr std::size_t kAuthInputRotOffset = 0x30;      // Vec2 {pitch, yaw}
inline constexpr std::size_t kAuthInputPosOffset = 0x38;      // Vec3 {x, y, z} (StateVectorComponent::mPos)
inline constexpr std::size_t kAuthInputMoveOffset = 0x64;     // Vec2 {strafe, forward}
inline constexpr std::size_t kAuthInputBitsOffset = 0x88;     // uint64_t bitset lower 64 bits
inline constexpr unsigned kAuthBitJumping = 6;
inline constexpr unsigned kAuthBitSneaking = 8;
inline constexpr unsigned kAuthBitWantUp = 16;
inline constexpr unsigned kAuthBitWantDown = 17;

// StartGamePacket
inline constexpr std::size_t kStartGameDimensionOffset = 0x088; // int32_t SpawnSettings::dimension
inline constexpr std::size_t kStartGameUniqueIdOffset = 0x488;  // int64_t ActorUniqueID
inline constexpr std::size_t kStartGameRuntimeIdOffset = 0x490; // uint64_t ActorRuntimeID
inline constexpr std::size_t kStartGamePosOffset = 0x49c;       // Vec3 {x, y, z}
inline constexpr std::size_t kStartGameRotOffset = 0x4a8;       // Vec2 {pitch, yaw}

// MovePlayerPacket
inline constexpr std::size_t kMovePlayerRuntimeIdOffset = 0x30; // uint64_t
inline constexpr std::size_t kMovePlayerPosOffset = 0x38;       // Vec3
inline constexpr std::size_t kMovePlayerRotOffset = 0x44;       // Vec2 {pitch, yaw}

// ChangeDimensionPacket
inline constexpr std::size_t kChangeDimIdOffset = 0x2c;         // int32_t
inline constexpr std::size_t kChangeDimPosOffset = 0x30;        // Vec3

// RespawnPacket
inline constexpr std::size_t kRespawnPosOffset = 0x30;          // Vec3
inline constexpr std::size_t kRespawnStateOffset = 0x3c;        // uint8_t (0 == SearchingForSpawn/ReadyToSpawn server pos)

// Actor / Mob / Player / Level offsets
inline constexpr std::size_t kActorEntityContextMapOffset = 0x010; // EntityRegistry*
inline constexpr std::size_t kActorEntityIdOffset = 0x018;         // uint32_t EntityId
inline constexpr std::size_t kPlayerGameModeOffset = 0x9e8;        // GameMode* on Player (updated across /gamemode)
inline constexpr std::size_t kActorHurtDurationOffset = 0x194;  // int32_t Mob hurt counter mirror
inline constexpr std::size_t kActorLevelOffset = 0x1d0;         // ILevel*
inline constexpr std::size_t kActorCategoriesOffset = 0x200;    // uint32_t ActorCategory bitmask (Player=1, Mob=2)
inline constexpr std::size_t kActorStateVectorOffset = 0x208;   // StateVectorComponent* (+0x00 mPos, +0x0c mPosPrev, +0x18 mPosDelta)
inline constexpr std::size_t kActorAABBOffset = 0x210;          // AABBShapeComponent* (+0x00 Vec3 min, +0x0c Vec3 max, +0x18 Vec2 dim)
inline constexpr std::size_t kActorRotationOffset = 0x218;      // ActorRotationComponent* (+0x00 float pitch, +0x04 float yaw)
inline constexpr std::size_t kActorMobHurtTimeOffset = 0x408;   // MobHurtTimeComponent* (+0x00 int32_t mHurtTime, 10 -> 0)

inline constexpr std::size_t kLevelGetRuntimeEntityVtableByteOffset = 0x208;    // slot 65
inline constexpr std::size_t kLevelGetRuntimeActorListVtableByteOffset = 0xa30; // slot 326
inline constexpr std::size_t kActorLerpMotionVtableByteOffset = 0xb8;           // slot 23
inline constexpr std::size_t kActorIsAliveVtableByteOffset = 0x178;             // slot 47
inline constexpr std::size_t kLocalPlayerSwingVtableByteOffset = 0x370;         // slot 110
inline constexpr std::size_t kGameModeAttackVtableByteOffset = 0x80;            // slot 16
inline constexpr int kActorSwingSourceAttack = 4;

inline constexpr std::uint32_t kActorCategoryPlayer = 1U;
inline constexpr std::uint32_t kActorCategoryMob = 2U;

template <typename T>
inline T readField(const void* base, std::size_t byteOffset) noexcept {
    T out{};
    const auto* src = static_cast<const unsigned char*>(base) + byteOffset;
    std::memcpy(&out, src, sizeof(T));
    return out;
}

template <typename T>
inline void writeField(void* base, std::size_t byteOffset, const T& value) noexcept {
    auto* dst = static_cast<unsigned char*>(base) + byteOffset;
    std::memcpy(dst, &value, sizeof(T));
}

// Non-aborting equivalent of Actor::getRuntimeID() const (0xf566b90..0xf566c54).
// Queries EntityRegistry (actor+0x10) for RuntimeIDComponent (hash 0xfc0dbbb5) and returns 0
// instead of calling abort() (0x8536b5c) if the component is absent on a partially-built actor.
inline std::uint64_t tryReadActorRuntimeId(const void* actor) noexcept {
    if (actor == nullptr) return 0;
    const void* registry = readField<const void*>(actor, kActorEntityContextMapOffset);
    if (registry == nullptr) return 0;

    constexpr std::uint32_t kRuntimeIdTypeHash = 0xfc0dbbb5U;
    const auto* bucketsBegin = readField<const std::int64_t*>(registry, 0x38);
    const auto* bucketsEnd = readField<const std::int64_t*>(registry, 0x40);
    if (bucketsBegin == nullptr || bucketsEnd <= bucketsBegin) return 0;

    const auto bucketCount = static_cast<std::size_t>(bucketsEnd - bucketsBegin);
    const auto mask = static_cast<std::uint32_t>(bucketCount - 1);
    const auto entityId = readField<std::uint32_t>(actor, kActorEntityIdOffset);

    const auto* nodesBase = readField<const unsigned char*>(registry, 0x50);
    const auto* nodesSentinel = readField<const unsigned char*>(registry, 0x58);
    if (nodesBase == nullptr) return 0;

    const std::int64_t* link = bucketsBegin + (mask & kRuntimeIdTypeHash);
    const unsigned char* node = nullptr;
    for (int step = 0; step < 64; ++step) {
        const std::int64_t idx = *link;
        if (idx == -1 || idx < 0) return 0;
        node = nodesBase + static_cast<std::size_t>(idx) * 32U;
        if (readField<std::uint32_t>(node, 0x08) == kRuntimeIdTypeHash) break;
        link = reinterpret_cast<const std::int64_t*>(node);
        node = nullptr;
    }
    if (node == nullptr || node == nodesSentinel) return 0;

    const void* pool = readField<const void*>(node, 0x10);
    if (pool == nullptr) return 0;

    const auto* sparseBegin = readField<const void* const*>(pool, 0x08);
    const auto* sparseEnd = readField<const void* const*>(pool, 0x10);
    const std::size_t sparsePageIdx = (entityId >> 11U) & 0x7fU;
    if (sparseBegin == nullptr || sparseEnd <= sparseBegin
            || sparsePageIdx >= static_cast<std::size_t>(sparseEnd - sparseBegin)) {
        return 0;
    }

    const auto* sparsePage = static_cast<const std::uint32_t*>(sparseBegin[sparsePageIdx]);
    if (sparsePage == nullptr) return 0;

    const std::uint32_t denseEntry = sparsePage[entityId & 0x7ffU];
    if ((denseEntry ^ (entityId & 0xfffc0000U)) > 0x3fffeU) return 0;

    const auto* densePages = readField<const void* const*>(pool, 0x50);
    if (densePages == nullptr) return 0;

    const auto* densePage = static_cast<const std::uint64_t*>(densePages[(denseEntry >> 7U) & 0x7ffU]);
    if (densePage == nullptr) return 0;

    return densePage[denseEntry & 0x7fU];
}

inline Vec3 computeTargetHitPos(const void* actor, const Vec3& fallbackPos) noexcept {
    const void* aabbComp = actor ? readField<const void*>(actor, kActorAABBOffset) : nullptr;
    if (aabbComp != nullptr) {
        const Vec3 min = readField<Vec3>(aabbComp, 0x00);
        const Vec3 max = readField<Vec3>(aabbComp, 0x0c);
        if (min.finite() && max.finite() && max.x >= min.x && max.y >= min.y && max.z >= min.z) {
            return Vec3{
                (min.x + max.x) * 0.5f,
                (min.y + max.y) * 0.5f,
                (min.z + max.z) * 0.5f
            };
        }
    }
    return fallbackPos;
}

inline int readActorHurtTicks(const void* actor) noexcept {
    if (actor == nullptr) return 0;
    const void* hurtComp = readField<const void*>(actor, kActorMobHurtTimeOffset);
    if (hurtComp != nullptr) {
        const int hurt = static_cast<int>(readField<std::int32_t>(hurtComp, 0));
        if (hurt > 0) return hurt;
    }
    const int mirror = static_cast<int>(readField<std::int32_t>(actor, kActorHurtDurationOffset));
    return mirror > 0 ? mirror : 0;
}

inline PacketEvent decodeAuthInputPacket(const void* packet) noexcept {
    PacketEvent ev{};
    ev.kind = PacketEventKind::AuthInput;
    ev.direction = Direction::ClientToServer;
    ev.pitch = readField<float>(packet, kAuthInputRotOffset);
    ev.yaw = readField<float>(packet, kAuthInputRotOffset + sizeof(float));
    ev.position = readField<Vec3>(packet, kAuthInputPosOffset);
    ev.input.strafe = readField<float>(packet, kAuthInputMoveOffset);
    ev.input.forward = readField<float>(packet, kAuthInputMoveOffset + sizeof(float));
    const auto bits = readField<std::uint64_t>(packet, kAuthInputBitsOffset);
    ev.input.jumping = ((bits >> kAuthBitJumping) & 1ULL) != 0ULL;
    ev.input.sneaking = ((bits >> kAuthBitSneaking) & 1ULL) != 0ULL;
    ev.input.wantUp = ((bits >> kAuthBitWantUp) & 1ULL) != 0ULL;
    ev.input.wantDown = ((bits >> kAuthBitWantDown) & 1ULL) != 0ULL;
    return ev;
}

inline PacketEvent decodeStartGamePacket(const void* packet) noexcept {
    PacketEvent ev{};
    ev.kind = PacketEventKind::StartGame;
    ev.direction = Direction::ServerToClient;
    ev.dimension = static_cast<int>(readField<std::int32_t>(packet, kStartGameDimensionOffset));
    ev.uniqueId = readField<std::int64_t>(packet, kStartGameUniqueIdOffset);
    ev.runtimeId = readField<std::uint64_t>(packet, kStartGameRuntimeIdOffset);
    ev.position = readField<Vec3>(packet, kStartGamePosOffset);
    ev.pitch = readField<float>(packet, kStartGameRotOffset);
    ev.yaw = readField<float>(packet, kStartGameRotOffset + sizeof(float));
    return ev;
}

inline PacketEvent decodeMovePlayerPacket(const void* packet) noexcept {
    PacketEvent ev{};
    ev.kind = PacketEventKind::MovePlayer;
    ev.direction = Direction::ServerToClient;
    ev.runtimeId = readField<std::uint64_t>(packet, kMovePlayerRuntimeIdOffset);
    ev.position = readField<Vec3>(packet, kMovePlayerPosOffset);
    ev.pitch = readField<float>(packet, kMovePlayerRotOffset);
    ev.yaw = readField<float>(packet, kMovePlayerRotOffset + sizeof(float));
    return ev;
}

inline PacketEvent decodeChangeDimensionPacket(const void* packet) noexcept {
    PacketEvent ev{};
    ev.kind = PacketEventKind::ChangeDimension;
    ev.direction = Direction::ServerToClient;
    ev.dimension = static_cast<int>(readField<std::int32_t>(packet, kChangeDimIdOffset));
    ev.position = readField<Vec3>(packet, kChangeDimPosOffset);
    return ev;
}

inline PacketEvent decodeRespawnPacket(const void* packet) noexcept {
    PacketEvent ev{};
    ev.kind = PacketEventKind::Respawn;
    ev.direction = Direction::ServerToClient;
    ev.position = readField<Vec3>(packet, kRespawnPosOffset);
    const auto state = readField<std::uint8_t>(packet, kRespawnStateOffset);
    ev.respawnPositionReady = (state == 0U);
    return ev;
}

} // namespace clientbase::modules::abi
