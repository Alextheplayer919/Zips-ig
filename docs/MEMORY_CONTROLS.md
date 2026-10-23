# 0.6.0 — external controls and first read-only memory integration

## User-selected architecture

One APK; **controls in the normal host UI process**, memory backend in **:game_host**. The user explicitly chose a separate screen, not an in-game click GUI or companion overlay APK. No overlay view, button, renderer, input interception or GUI callback is attached to Minecraft. The abandoned in-game menu draft was removed before commit. Keep the 0.5.4 working baseline ([source/APK](HOSTED_LAUNCH_SUCCESS.md)) intact as reference.

The launcher has a new dark/cyan card layout: primary Bedrock launch, external controls, collapsed session diagnostics and collapsed legacy developer tools. Existing report save/share/exit collection and demo/probe APIs remain available. Controls are an independent non-exported, resizable **singleTask Activity** with affinity `dev.zips.clientbase.host.controls`, in the UI process, not :game_host. This permits two Recents tasks without splitting the APK.

### Keeping the game visible

Open Controls first, then launch Bedrock from the launcher. On a supported phone, Recents can place the Controls task beside the game's task in **split-screen**. Android 13 multi-resume is a possible way to keep both visible/resumed. This is **not a promise** that the OEM permits same-app split-screen or Minecraft continues gameplay while unfocused/resized. No split-screen forcing, hidden API, lifecycle suppression, background-ticking patch or overlay permission. A full-screen app switch can still pause/background the game normally. Multi-window compatibility and physical UI appearance require a real-phone test; emulator activity/task tests are not proof of Minecraft multi-window behavior.

## Read-only backend

Only a manual **Refresh memory snapshot** issues the fixed Messenger operation **2** to the existing private GameHostService. No polling/auto-refresh and no memory work in the game's frame loop or main looper: the service dispatches one worker, with a busy gate. It does not call prepare, class initialization, SDK startup, Activity launch or the controller's start/reconnect path. No game present yields an unavailable report; binding may create an empty host service process, never an engine. Reply/request IDs, Binder sending UID, expected own UID/PID envelope and private binding are checked. The screen cancels/unbinds on stop; requests have an eight-second UI deadline. No arbitrary addresses, paths, offsets or write payloads cross IPC.

The backend requires the dedicated game process and a prepared created session. It resolves **EngineNativeBridge through that same prepared guest loader**, preserving the single native-library owner. The bridge's new function:

- Inspects **already loaded** modules via `dl_iterate_phdr`, matches the exact selected engine path or its canonical filesystem alias; does **not** dlopen/dlsym/call an engine function.
- Reports process PID/UID, module load bias (ASLR/run-specific) and up to 16 **ELF PT_LOAD segment ranges/flags**, not a claimed live page-permission audit (RELRO may differ).
- Uses kernel-mediated **read-only /proc/self/mem pread** to read the 64-byte mapped ELF header and a mapped readable PT_NOTE region of at most 64 KiB. No unsafe game-pointer dereference or arbitrary address API. If platform policy or reads deny access, report unavailable without patching permissions/crashing deliberately.
- Parses the GNU build ID from memory with bounds checks. Compare it with the known supplied engine **afa62bd3385161f1facfe36aad58ac63998f46d2** and require an ELF64 header read before displaying **ENGINE IDENTITY VERIFIED**. The unchanged file SHA-256 was verified at startup; the report does not pretend to hash the entire current memory image.

This is real in-process memory reading of the loaded ELF, **not player/world field integration, memory writing, hooks, a module framework or arbitrary scanning**. Unknown/failed identity never authorizes offsets. There are no pretend gameplay toggles. Future implemented backend features can add explicit IPC commands; no command is implemented solely by drawing a toggle. Snapshot/report is finite by design; existing warning/error/fatal TXT export remains uncapped by app byte count.

## Preservation and coverage

Original wrapper/lifecycle and SDK init order remain unchanged; no new permissions, Minecraft class definitions, engine file/DEX patches, root/ptrace, package spoofing or FCM/token behavior changes. Only our own native bridge is extended. 0.6.0 / code13 is the integration build; 0.5.4/code12 remains the reported-working rollback reference.

Six new Android regressions: actual JNI header/build-ID/segment reads of our own copied library and rejection of an unloaded candidate without loading it; UI-process game-reader rejection; malformed/foreign protocol checks; real memory IPC with no game does not change game preparation stages; independent controls task/UI identity and recreation; collapsed retained developer tools. Existing coverage remains enabled. No fake Minecraft implementation or real ARM64 game runs in CI.

Android compilation/APK/emulator validation passed for the corrected implementation below; original-game phone integration remains pending. Next phone acceptance after validation: launch still works; external controls open in their own task; refresh reports the **game** PID and verified in-memory engine identity or an explicit unavailable error. Copy/share that memory report, not another giant log. Split-screen is an optional compatibility test, not a prerequisite for the first memory snapshot.


### First emulator result

Run 39992531136 built and passed APK checks, but the new real-library memory regression found the lookup missed the loaded copied library through Android's data-directory path alias. The implementation now compares canonical filesystem identities as well as raw paths; it still never loads a missing candidate. The same production test checks both absolute and canonical paths. No test is skipped or relaxed; this first artifact is not the deliverable. Corrected validation passed below.


## Validated integration build

[Android 13 CI run 39993673423](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39993673423) passed for app/test commit **7170619df05a2ebf9fd25ab821bf4b729285d2db**, job **120552302188**, **5m50s**, host **0.6.0 / code13**. Build/APK definition-exclusion checks and **54 Android 13 x86_64 regression cases** passed, including real mapped-memory ELF header/build-ID/segment reads against our own copied native library via both absolute and canonical paths, rejection without loading a candidate, actual no-game memory IPC, protocol/ownership checks, controls task/recreation and retained collapsed developer tools. **26 local source/native guards** pass. No test disabled or read assertion weakened. CI does not execute original ARM64 Bedrock or prove physical multi-window/gameplay compatibility. Local artifact XML parsing is not claimed.

- [APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39993673423/artifacts/12518682891): **client-base-debug-86**, **6,234,777 bytes**. Install only **client-base-host-debug.apk**.
- [Reports](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39993673423/artifacts/12518288786): **client-base-device-tests-86**, **262,603 bytes**.
- Retained engine archive blob **c78f6e8642d334916d247487897eb871ad27c160**; original wrapper/lifecycle, SDK startup order and permissions are unchanged from the user-reported working baseline. No native game code is patched or packaged. Do not use the earlier failed integration artifact.

### First phone integration check

1. Keep the 0.5.4 APK as reference; update only the host to 0.6.0. Keep Minecraft unchanged. Preserve reports before uninstalling only the host if debug signatures conflict.
2. Tap **Open client controls** once, making its separate task available in Recents. Return to the launcher task via Recents and **Launch Bedrock Activity (experimental)** normally.
3. Once the game is running, switch via Recents to **Zips Controls** (do not Back/destroy the game). Tap **Refresh memory snapshot**. A normal full-screen switch may pause the game, but its loaded process can still be inspected if Android keeps it alive.
4. Use **Copy memory report** and share that small report: expected game PID/UID, in-memory build ID, header status and verified identity; an explicit unavailable result is useful too. No archive import, demo/link/bootstrap repeat or giant trace is requested.
5. Optionally try Recents split-screen with Controls beside the game. Report whether your device offers it and whether the game remains usable. This is optional compatibility exploration, not a claimed pause bypass or required memory-reading mechanism.
