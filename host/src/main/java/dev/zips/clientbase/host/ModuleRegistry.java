package dev.zips.clientbase.host;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/** Build-time registrations, not live backend capabilities or user-editable enabled flags. */
final class ModuleRegistry {
    static final class Entry {
        final String id, name, description, blocker;
        final ModuleCategory category;
        final int viewId;
        Entry(String id, String name, ModuleCategory category, int viewId, String description, String blocker) {
            this.id=id;this.name=name;this.category=category;this.viewId=viewId;
            this.description=description;this.blocker=blocker;
        }
    }
    private static final List<Entry> ALL=Collections.unmodifiableList(Arrays.asList(
        new Entry("motion_fly","MotionFly",ModuleCategory.MOVEMENT,R.id.module_motion_fly,
            "Packet-driven flight", "Unavailable · live packet session not connected"),
        new Entry("kill_aura","KillAura",ModuleCategory.COMBAT,R.id.module_kill_aura,
            "Native target selection and attacks", "Unavailable · native attack adapter not connected")
    ));
    static List<Entry> all(){return ALL;}
    static List<Entry> forCategory(ModuleCategory category){
        List<Entry> result=new ArrayList<>();
        for(Entry entry:ALL)if(entry.category==category)result.add(entry);
        return Collections.unmodifiableList(result);
    }
    private ModuleRegistry(){}
}
