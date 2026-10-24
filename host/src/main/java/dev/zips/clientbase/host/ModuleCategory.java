package dev.zips.clientbase.host;

/** Navigation only. Categories do not register or enable unimplemented modules. */
enum ModuleCategory {
    MOVEMENT("Movement", R.id.category_movement),
    COMBAT("Combat", R.id.category_combat),
    VISUAL("Visual", R.id.category_visual),
    MISC("Misc", R.id.category_misc);

    final String title;
    final int viewId;
    ModuleCategory(String title, int viewId) { this.title = title; this.viewId = viewId; }
}
