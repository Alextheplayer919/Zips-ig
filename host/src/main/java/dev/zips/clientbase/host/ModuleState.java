package dev.zips.clientbase.host;

import java.util.HashMap;
import java.util.Map;

/** Parsed snapshot from the private :game_host module engine. Never fabricates live state. */
final class ModuleState {
    static final String MOTION_FLY = "motion_fly";
    static final String KILL_AURA = "kill_aura";

    final boolean supported;
    final boolean hooksInstalled;
    final boolean inWorld;
    final boolean flyArmed;
    final boolean flyActive;
    final boolean auraArmed;
    final boolean auraActive;
    final boolean attackMobs;
    final boolean multiTarget;
    final boolean checkHurtTime;
    final long selfRuntimeId;
    final long authPackets;
    final long motionInjections;
    final long auraTicks;
    final long auraAttacks;
    final String message;
    final String diagnosticReport;

    private ModuleState(
            boolean supported, boolean hooksInstalled, boolean inWorld,
            boolean flyArmed, boolean flyActive, boolean auraArmed, boolean auraActive,
            boolean attackMobs, boolean multiTarget, boolean checkHurtTime,
            long selfRuntimeId, long authPackets, long motionInjections,
            long auraTicks, long auraAttacks,
            String message, String diagnosticReport) {
        this.supported = supported;
        this.hooksInstalled = hooksInstalled;
        this.inWorld = inWorld;
        this.flyArmed = flyArmed;
        this.flyActive = flyActive;
        this.auraArmed = auraArmed;
        this.auraActive = auraActive;
        this.attackMobs = attackMobs;
        this.multiTarget = multiTarget;
        this.checkHurtTime = checkHurtTime;
        this.selfRuntimeId = selfRuntimeId;
        this.authPackets = authPackets;
        this.motionInjections = motionInjections;
        this.auraTicks = auraTicks;
        this.auraAttacks = auraAttacks;
        this.message = message;
        this.diagnosticReport = diagnosticReport;
    }

    static ModuleState initial() {
        return unavailable("Launch Bedrock first to enable live modules", true, false, false);
    }

    static ModuleState unavailable(String reason, boolean attackMobs, boolean multiTarget, boolean checkHurtTime) {
        return new ModuleState(
                false, false, false,
                false, false, false, false,
                attackMobs, multiTarget, checkHurtTime,
                0L, 0L, 0L, 0L, 0L,
                reason,
                "INTERNAL MODULE ENGINE\nStatus: " + reason + "\nHooks installed: NO\n");
    }

    static String unavailablePayload(String reason, boolean attackMobs, boolean multiTarget, boolean checkHurtTime) {
        return "supported=0\n"
                + "hooks_installed=0\n"
                + "in_world=0\n"
                + "fly_armed=0\n"
                + "fly_active=0\n"
                + "aura_armed=0\n"
                + "aura_active=0\n"
                + "attack_mobs=" + (attackMobs ? 1 : 0) + "\n"
                + "multi_target=" + (multiTarget ? 1 : 0) + "\n"
                + "check_hurt_time=" + (checkHurtTime ? 1 : 0) + "\n"
                + "self_runtime_id=0\n"
                + "auth_packets=0\n"
                + "motion_injections=0\n"
                + "aura_ticks=0\n"
                + "aura_attacks=0\n"
                + "message=" + reason + "\n"
                + "---\n"
                + "INTERNAL MODULE ENGINE\n"
                + "Status: " + reason + "\n"
                + "Hooks installed: NO\n";
    }

    static ModuleState parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return unavailable("Empty module engine response", true, false, false);
        }
        int sep = raw.indexOf("\n---\n");
        String header = sep >= 0 ? raw.substring(0, sep) : raw;
        String report = sep >= 0 ? raw.substring(sep + 5) : raw;
        Map<String, String> kv = new HashMap<>();
        for (String line : header.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return new ModuleState(
                flag(kv, "supported", false),
                flag(kv, "hooks_installed", false),
                flag(kv, "in_world", false),
                flag(kv, "fly_armed", false),
                flag(kv, "fly_active", false),
                flag(kv, "aura_armed", false),
                flag(kv, "aura_active", false),
                flag(kv, "attack_mobs", true),
                flag(kv, "multi_target", false),
                flag(kv, "check_hurt_time", false),
                num(kv, "self_runtime_id"),
                num(kv, "auth_packets"),
                num(kv, "motion_injections"),
                num(kv, "aura_ticks"),
                num(kv, "aura_attacks"),
                kv.getOrDefault("message", "Module status updated"),
                report);
    }

    boolean isArmed(String moduleId) {
        if (MOTION_FLY.equals(moduleId)) return flyArmed;
        if (KILL_AURA.equals(moduleId)) return auraArmed;
        return false;
    }

    boolean isActive(String moduleId) {
        if (MOTION_FLY.equals(moduleId)) return flyActive;
        if (KILL_AURA.equals(moduleId)) return auraActive;
        return false;
    }

    String badgeText(String moduleId) {
        if (isActive(moduleId)) return "ON";
        if (isArmed(moduleId)) return "ARMED";
        return "OFF";
    }

    String statusText(String moduleId) {
        if (isActive(moduleId)) {
            return MOTION_FLY.equals(moduleId)
                    ? "Active in world · packet motion injection live"
                    : "Active in world · native GameMode attacks live";
        }
        if (isArmed(moduleId)) {
            return "Armed · enter a world to activate";
        }
        return message;
    }

    private static boolean flag(Map<String, String> kv, String key, boolean fallback) {
        String v = kv.get(key);
        if (v == null) return fallback;
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static long num(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null) return 0L;
        try { return Long.parseUnsignedLong(v); }
        catch (NumberFormatException ignored) { return 0L; }
    }
}
