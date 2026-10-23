#pragma once

#include "module_runtime.h"
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace clientbase::modules::abi {

// Exact static-verified constants for supplied Android ARM64 libminecraftpe.so (1.26.50.4):
// SHA-256: fac2be5b17ef79419ebd66fe4f9e362a48d7b2067d5e1f649e65c36edf2927e1
// GNU Build ID: afa62bd3385161f1facfe36aad58ac63998f46d2

struct VtableSpec {
    std::uintptr_t addressPoint;
    std::uintptr_t typeInfo;
};

struct MethodSpec {
    std::uintptr_t vtableAddressPoint;
    std::size_t slotIndex;
    std::uintptr_t functionOffset;
    std::uint32_t insn0;
    std::uint32_t insn1;
};

struct FunctionSpec {
    std::uintptr_t functionOffset;
    std::uint32_t insn0;
    std::uint32_t insn1;
};

// RTTI-verified vtable address points and typeinfo addresses.
inline constexpr VtableSpec kLoopbackSenderVt   {0x12e2f288, 0x12e2f9c8}; // 20LoopbackPacketSender
inline constexpr VtableSpec kLegacyClientNetVt  {0x12d248c8, 0x12d25348}; // 26LegacyClientNetworkHandler
inline constexpr VtableSpec kGameModeVt         {0x13016718, 0x13016ad0}; // 8GameMode
inline constexpr VtableSpec kSurvivalModeVt     {0x13016848, 0x13016b08}; // 12SurvivalMode
inline constexpr VtableSpec kLocalPlayerVt      {0x12d08918, 0x12d09928}; // 11LocalPlayer
inline constexpr VtableSpec kAuthInputPacketVt  {0x12e86ab0, 0x12e7fbd8}; // 21PlayerAuthInputPacket
inline constexpr VtableSpec kSetMotionPacketVt  {0x12e727c8, 0x12e730b0}; // 20SetActorMotionPacket

inline constexpr VtableSpec kRequiredVtables[] = {
    kLoopbackSenderVt, kLegacyClientNetVt, kGameModeVt, kSurvivalModeVt,
    kLocalPlayerVt, kAuthInputPacketVt, kSetMotionPacketVt
};

// Hooked RELRO slots (14 slots total, zero .text instruction edits).
inline constexpr MethodSpec kLoopbackSendToServer {0x12e2f288,   5, 0x0c68fd38, 0xa9bb7bfdU, 0xf9000bfcU};

inline constexpr MethodSpec kLcnhDtorComplete     {0x12d248c8,   0, 0x0ad6a638, 0xa9bc7bfdU, 0xf9000bf7U};
inline constexpr MethodSpec kLcnhDtorDeleting     {0x12d248c8,   1, 0x0ad6aa14, 0xa9be7bfdU, 0xf9000bf3U};
inline constexpr MethodSpec kLcnhDisconnect       {0x12d248c8,  19, 0x0ad6bca8, 0xa9ba7bfdU, 0xf9000bfcU};
inline constexpr MethodSpec kLcnhStartGame        {0x12d248c8,  37, 0x0ad6d300, 0xa9ba7bfdU, 0xa9016ffcU};
inline constexpr MethodSpec kLcnhMovePlayer       {0x12d248c8,  47, 0x0ad749a0, 0xd10243ffU, 0xfd0023eaU};
inline constexpr MethodSpec kLcnhChangeDimension  {0x12d248c8,  99, 0x0ad7c52c, 0xf9411808U, 0xb4000388U};
inline constexpr MethodSpec kLcnhRespawn          {0x12d248c8, 170, 0x09ed3754, 0xa9bc7bfdU, 0xf9000bf7U};

inline constexpr MethodSpec kGameModeDtorComplete {0x13016718,   0, 0x0f89efbc, 0xa9be7bfdU, 0xf9000bf3U};
inline constexpr MethodSpec kGameModeDtorDeleting {0x13016718,   1, 0x0f89f01c, 0xa9be7bfdU, 0xf9000bf3U};
inline constexpr MethodSpec kGameModeTick         {0x13016718,  10, 0x0f8a33ec, 0xa9be7bfdU, 0xf9000bf3U};

inline constexpr MethodSpec kSurvivalDtorComplete {0x13016848,   0, 0x0f8a4c24, 0xa9be7bfdU, 0xf9000bf3U};
inline constexpr MethodSpec kSurvivalDtorDeleting {0x13016848,   1, 0x0f8a4cc0, 0xa9be7bfdU, 0xf9000bf3U};
inline constexpr MethodSpec kSurvivalTick         {0x13016848,  10, 0x0f8a3dcc, 0xd10383ffU, 0xa90a7bfdU};

// Verified non-hooked virtual methods and direct helpers.
inline constexpr MethodSpec kLcnhSetActorMotion   {0x12d248c8,  67, 0x0ad76d18, 0xd10183ffU, 0xa9027bfdU};
inline constexpr MethodSpec kGameModeAttack       {0x13016718,  16, 0x0f89f570, 0xaa0203e3U, 0x52800022U};
inline constexpr MethodSpec kSurvivalAttack       {0x13016848,  16, 0x0f8a3adc, 0x39432008U, 0xaa0203e3U};

inline constexpr FunctionSpec kSetActorMotionCtor {0x0d14042c, 0xf0fab3e8U, 0x7900201fU};
inline constexpr FunctionSpec kActorGetRuntimeId  {0x0f566b90, 0xa9bf7bfdU, 0x910003fdU};
inline constexpr FunctionSpec kActorGetUniqueId   {0x0f566e30, 0xa9be7bfdU, 0xf9000bf3U};
inline constexpr FunctionSpec kActorGetPosition   {0x0f56ab44, 0xf9410408U, 0x91003100U};
inline constexpr FunctionSpec kActorGetHurtTime   {0x0f590ef4, 0x91048000U, 0x52800161U};
inline constexpr FunctionSpec kActorCanSee        {0x0f5841c0, 0xd10183ffU, 0xa9027bfdU};
inline constexpr FunctionSpec kPlayerGetDimension {0x0f9e34f8, 0xa9be7bfdU, 0xf9000bf3U};
inline constexpr std::uintptr_t kEngineOperatorDelete = 0x12911250; // _ZdlPv PLT

// Verified vtable byte offsets on runtime polymorphic objects.
inline constexpr std::size_t kGameModePlayerOffset         = 0x008; // Player* GameMode::mPlayer
inline constexpr std::size_t kGameModeAttackVtOffset       = 0x080; // slot 16: bool attack(Actor&, const Vec3&)
inline constexpr std::size_t kActorLerpMotionVtOffset      = 0x0b8; // slot 23: void lerpMotion(const Vec3&)
inline constexpr std::size_t kActorIsAliveVtOffset         = 0x178; // slot 47: bool isAlive() const
inline constexpr std::size_t kPlayerSwingVtOffset          = 0x370; // slot 110: bool swing(ActorSwingSource, bool)
inline constexpr std::size_t kLevelGetRuntimeEntityVtOff   = 0x208; // Actor* getRuntimeEntity(ActorRuntimeID, bool)
inline constexpr std::size_t kLevelGetRuntimeActorsVtOff   = 0xa30; // std::vector<Actor*> getRuntimeActorList() const
inline constexpr std::size_t kClientInstanceGetMcVtOff     = 0x280; // Minecraft* getServerData/Minecraft()

// Verified object field offsets.
inline constexpr std::size_t kLoopbackSenderKindOffset     = 0x028; // uint32_t: 0 = Client, 1 = Server
inline constexpr std::size_t kActorEntityContextMapOffset  = 0x010; // EntityRegistry* check
inline constexpr std::size_t kActorDimensionPtrOffset      = 0x1c0; // Dimension* raw
inline constexpr std::size_t kActorDimensionWeakOffset     = 0x1c8; // WeakRef<Dimension> control
inline constexpr std::size_t kActorLevelOffset             = 0x1d0; // ILevel* mLevel
inline constexpr std::size_t kActorCategoriesOffset        = 0x200; // uint32_t ActorCategory bitmask (1=Player, 2=Mob)
inline constexpr std::size_t kActorStateVectorOffset       = 0x208; // StateVectorComponent* (+0x0c mPos)
inline constexpr std::size_t kActorRotationOffset          = 0x210; // ActorRotationComponent* (pitch +0, yaw +4)
inline constexpr std::size_t kLocalPlayerClientInstOffset  = 0xc88; // IClientInstance* mClient
inline constexpr std::size_t kMinecraftGameSessionOffset   = 0x0c0; // GameSession*
inline constexpr std::size_t kGameSessionNetCbCtrlOffset   = 0x030; // NonOwnerPointer<NetEventCallback> control
inline constexpr std::size_t kGameSessionNetCbPtrOffset    = 0x040; // NonOwnerPointer<NetEventCallback> pointer

inline constexpr std::uint32_t kActorCategoryPlayer        = 1U;
inline constexpr std::uint32_t kActorCategoryMob           = 2U;
inline constexpr int kActorSwingSourceAttack               = 4;
inline constexpr int kCanSeeShapeCollision                 = 1;

// Verified packet field offsets (from Packet::validate / Cereal / LegacyClientNetworkHandler::handle).
inline constexpr std::size_t kAuthInputRotOffset           = 0x030; // Vec2 {pitch, yaw}
inline constexpr std::size_t kAuthInputPosOffset           = 0x038; // Vec3
inline constexpr std::size_t kAuthInputMoveOffset          = 0x064; // Vec2 {strafe, forward}
inline constexpr std::size_t kAuthInputBitsOffset          = 0x088; // uint64_t low word of std::bitset
inline constexpr unsigned kAuthBitJumping                  = 6U;
inline constexpr unsigned kAuthBitSneaking                 = 8U;
inline constexpr unsigned kAuthBitWantUp                   = 16U;
inline constexpr unsigned kAuthBitWantDown                 = 17U;

inline constexpr std::size_t kStartGameDimensionOffset     = 0x088; // int32_t SpawnSettings::dimension
inline constexpr std::size_t kStartGameUniqueIdOffset      = 0x488; // int64_t mEntityId
inline constexpr std::size_t kStartGameRuntimeIdOffset     = 0x490; // uint64_t mRuntimeId
inline constexpr std::size_t kStartGamePosOffset           = 0x49c; // Vec3 mPos
inline constexpr std::size_t kStartGameRotOffset           = 0x4a8; // Vec2 {pitch, yaw}

inline constexpr std::size_t kMovePlayerRuntimeIdOffset    = 0x030; // uint64_t mPlayerID
inline constexpr std::size_t kMovePlayerPosOffset          = 0x038; // Vec3 mPos
inline constexpr std::size_t kMovePlayerRotOffset          = 0x044; // Vec2 {pitch, yaw}

inline constexpr std::size_t kRespawnPosOffset             = 0x030; // Vec3 mPos
inline constexpr std::size_t kRespawnStateOffset           = 0x03c; // uint8_t (0=Searching, 1=ReadyToSpawn, 2=ClientAck)

inline constexpr std::size_t kChangeDimIdOffset            = 0x02c; // int32_t mDimensionId
inline constexpr std::size_t kChangeDimPosOffset           = 0x030; // Vec3 mPos

inline constexpr std::size_t kSetMotionRuntimeIdOffset     = 0x030; // uint64_t mRuntimeId
inline constexpr std::size_t kSetMotionVecOffset           = 0x038; // Vec3 mMotion
inline constexpr std::size_t kSetMotionTickOffset          = 0x048; // uint64_t mTick (0 = direct client motion)
inline constexpr std::size_t kSetMotionStorageBytes        = 128;   // >= 0x58 actual size, 16-byte aligned

template <typename T>
inline T readField(const void* base, std::size_t offset) noexcept {
    T value{};
    std::memcpy(&value, static_cast<const unsigned char*>(base) + offset, sizeof(T));
    return value;
}

template <typename T>
inline void writeField(void* base, std::size_t offset, const T& value) noexcept {
    std::memcpy(static_cast<unsigned char*>(base) + offset, &value, sizeof(T));
}

inline PacketEvent decodeAuthInputPacket(const void* pkt) noexcept {
    PacketEvent ev;
    ev.kind = PacketEventKind::AuthInput;
    ev.direction = Direction::ClientToServer;
    ev.pitch = readField<float>(pkt, kAuthInputRotOffset);
    ev.yaw = readField<float>(pkt, kAuthInputRotOffset + sizeof(float));
    ev.position = readField<Vec3>(pkt, kAuthInputPosOffset);
    ev.input.kind = PacketKind::PlayerAuthInput;
    ev.input.direction = Direction::ClientToServer;
    ev.input.strafe = readField<float>(pkt, kAuthInputMoveOffset);
    ev.input.forward = readField<float>(pkt, kAuthInputMoveOffset + sizeof(float));
    ev.input.yawDegrees = ev.yaw;
    const auto bits = readField<std::uint64_t>(pkt, kAuthInputBitsOffset);
    ev.input.jumping = ((bits >> kAuthBitJumping) & 1ULL) != 0;
    ev.input.sneaking = ((bits >> kAuthBitSneaking) & 1ULL) != 0;
    ev.input.wantUp = ((bits >> kAuthBitWantUp) & 1ULL) != 0;
    ev.input.wantDown = ((bits >> kAuthBitWantDown) & 1ULL) != 0;
    return ev;
}

inline PacketEvent decodeStartGamePacket(const void* pkt) noexcept {
    PacketEvent ev;
    ev.kind = PacketEventKind::StartGame;
    ev.direction = Direction::ServerToClient;
    ev.dimension = static_cast<int>(readField<std::int32_t>(pkt, kStartGameDimensionOffset));
    ev.uniqueId = readField<std::int64_t>(pkt, kStartGameUniqueIdOffset);
    ev.runtimeId = readField<std::uint64_t>(pkt, kStartGameRuntimeIdOffset);
    ev.position = readField<Vec3>(pkt, kStartGamePosOffset);
    ev.pitch = readField<float>(pkt, kStartGameRotOffset);
    ev.yaw = readField<float>(pkt, kStartGameRotOffset + sizeof(float));
    return ev;
}

inline PacketEvent decodeMovePlayerPacket(const void* pkt) noexcept {
    PacketEvent ev;
    ev.kind = PacketEventKind::MovePlayer;
    ev.direction = Direction::ServerToClient;
    ev.runtimeId = readField<std::uint64_t>(pkt, kMovePlayerRuntimeIdOffset);
    ev.position = readField<Vec3>(pkt, kMovePlayerPosOffset);
    ev.pitch = readField<float>(pkt, kMovePlayerRotOffset);
    ev.yaw = readField<float>(pkt, kMovePlayerRotOffset + sizeof(float));
    return ev;
}

inline PacketEvent decodeRespawnPacket(const void* pkt) noexcept {
    PacketEvent ev;
    ev.kind = PacketEventKind::Respawn;
    ev.direction = Direction::ServerToClient;
    ev.position = readField<Vec3>(pkt, kRespawnPosOffset);
    const auto state = readField<std::uint8_t>(pkt, kRespawnStateOffset);
    ev.respawnPositionReady = (state == 0U || state == 1U);
    return ev;
}

inline PacketEvent decodeChangeDimensionPacket(const void* pkt) noexcept {
    PacketEvent ev;
    ev.kind = PacketEventKind::ChangeDimension;
    ev.direction = Direction::ServerToClient;
    ev.dimension = static_cast<int>(readField<std::int32_t>(pkt, kChangeDimIdOffset));
    ev.position = readField<Vec3>(pkt, kChangeDimPosOffset);
    return ev;
}

} // namespace clientbase::modules::abi
