package dev.zips.clientbase.host;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Immutable build-time module registrations. Live enablement is decided strictly by :game_host. */
final class ModuleRegistry {
    static final class Entry {
        final String id, name, description, defaultStatus;
        final ModuleCategory category;
        final int viewId, toggleId, statusId;

        Entry(String id, String name, ModuleCategory category,
              int viewId, int toggleId, int statusId,
              String description, String defaultStatus) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.viewId = viewId;
            this.toggleId = toggleId;
            this.statusId = statusId;
            this.description = description;
            this.defaultStatus = defaultStatus;
        }
    }

    private static final List<Entry> ALL = Collections.unmodifiableList(Arrays.asList(
        new Entry(ModuleState.MOTION_FLY, "MotionFly", ModuleCategory.MOVEMENT,
            R.id.module_motion_fly, R.id.module_motion_fly_toggle, R.id.module_motion_fly_status,
            "Internal packet-driven flight (AuthInput -> SetActorMotion)",
            "OFF · Launch Bedrock to enable"),
        new Entry(ModuleState.KILL_AURA, "KillAura", ModuleCategory.COMBAT,
            R.id.module_kill_aura, R.id.module_kill_aura_toggle, R.id.module_kill_aura_status,
            "Native GameMode target selection, attack and swing",
            "OFF · Launch Bedrock to enable")
    ));

    static List<Entry> all() { return ALL; }

    static List<Entry> forCategory(ModuleCategory category) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : ALL) {
            if (entry.category == category) result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }

    private ModuleRegistry() {}
}
