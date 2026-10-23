# 0.6.2 — category click GUI, diagnostics outside the game

The user reports that the 0.6.1 external overlay works on their phone. This is user-reported acceptance, not a captured FPS/focus/rotation test. They now request a normal client-style category menu, with all technical memory/copy/report controls kept in the host APK outside the floating game UI.

## UI-only change

- Floating ZIPS chip opens a dark/cyan two-pane menu: **Movement, Combat, Visual, Misc** navigation, selected-category highlighting, category heading and honest empty state. All four are empty; no pretend modules or enabled switches.
- Selected category survives Close/reopen for the life of the overlay service. Stop/restart resets to Movement. No polling, game IPC, clipboard operation or memory report remains in the overlay.
- Header drag/Close and footer Stop remain; the bounded window is at most 480×360dp with inset-aware clamping and independently scrollable category/content panes for small screens. Non-focusable flags and private foreground service/permission lifecycle are unchanged. Existing explicit Enable flow remains; no auto-grant/auto-start or new permission.
- Launcher/separate Controls retain real **Refresh memory snapshot / Copy memory report**, and existing exports/developer tools. Available before launch; inspecting a live engine still requires a running prepared game. No requirement to show diagnostics over Minecraft.
- Native engine/backend, guest wrappers/lifecycle, startup/SDK pins and original engine archive are unchanged. Host version 0.6.2/code15; no engine or Minecraft modification.

## Validation gate

28 local source/native guards pass. Full Android build/APK/emulator validation pending. The real overlay regression now switches every category, checks exclusive selection and Close/reopen state, and checks old memory controls are absent. Existing native/IPC regressions remain. A new real Controls UI regression performs the no-game memory request and copies its result to the Android clipboard without preparing/launching the game. Do not deliver until full validation passes.

## Inputs for actual modules

No additional file is needed for this UI, and no arbitrary archive resend is requested. We already have the exact supplied engine and reviewed DEX/startup metadata. Categories alone do not supply native APIs: the current backend can identify the loaded engine, not get a player/world pointer or modify gameplay.

Start by selecting **one concrete first module and its intended behavior**, rather than developing four categories at once. Then identify its exact required engine API/event/read/write semantics and lifecycle/thread ownership. If available, the most useful additional developer material is a **symbol/map file or C++ headers/source matched to this exact engine build ID** (`afa62bd3385161f1facfe36aad58ac63998f46d2`), especially the interface needed by that module. Unmatched offsets, another version's SDK, or merely drawing toggles cannot prove correct calls/layouts. These files are helpful, not something the user must magically produce; if absent, targeted investigation/validation is needed and feasibility is not guaranteed. Do not restore old archives/history or silently switch game versions.

Any concrete native implementation needs version/identity gating, safe session/pointer lifecycle, thread-appropriate execution, explicit IPC commands, honest unavailable states and tests before a toggle becomes functional. Ask only for the specific missing input after choosing the first module. No root, private worlds, account credentials, giant memory dump or generic repeat bootstrap trace is requested now.
