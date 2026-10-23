# 0.6.1 — optional external click GUI in Java

The user supplied a Compose overlay sketch, then authorized using our existing Java/native-view implementation style instead of Kotlin. This revises the earlier screen-only preference: **the separate Controls task remains the fallback; the new overlay is optional**. One APK, normal host UI process, fixed memory IPC to :game_host. No view or renderer is injected into Minecraft, no wrapper/lifecycle/SDK/native-memory backend changes. No fake module switches or Kotlin/Compose dependencies.

> **0.6.2 update:** The user reports this overlay works and requested a category-only menu. [Current click GUI / validated APK](CLICK_GUI.md): Movement, Combat, Visual, Misc; memory diagnostics remain in host Controls, not the overlay. This page records the preceding 0.6.1 Memory menu and service/permission design.

## Permission and foreground lifecycle

From **Open client controls → Enable external overlay**:

- If draw-over-other-apps permission is missing, explain the scope and open the package's Android overlay settings only on explicit user action. Return and tap Enable again; no automatic start on returning from Settings and no game launch/memory read on permission grant.
- New manifest capabilities: **SYSTEM_ALERT_WINDOW**, **FOREGROUND_SERVICE**, **POST_NOTIFICATIONS**. Overlay permission is special user-controlled access; notifications are requested on Android 13 when the user chooses. If notifications are denied, offer explicit start without notification permission, explaining that notification-shade Stop may not be visible. Android still requires the foreground service and exposes active apps; Controls and the menu retain Stop buttons. No log-reading/storage/root/usage-access permission.
- Start only from the visible Controls button. Non-exported OverlayService runs in the normal UI process, uses **START_NOT_STICKY**, and promotes to foreground with a LOW-importance notification and immutable **Stop overlay** action. No boot/startup receiver, sticky resurrection or automatic game launch. Swiping app tasks may stop it (`stopWithTask=true`).
- Permission observer stops/cleans up on revocation. Repeated starts do not duplicate windows. Stop cancels pending memory IPC, removes both windows, unregisters observers and removes the foreground notification; it does **not** stop the hosted game. Denial/attachment errors fail closed with a visible message and log.

These grants belong to the host UID, which is shared by trusted original guest code; this is not a guest security sandbox. Enabling the overlay is explicit consent. The overlay is not automatically limited to Minecraft: it may appear over other apps until stopped. Sensitive/system apps may hide it.

## Window behavior

A small draggable **ZIPS** chip opens a draggable bounded dark/cyan Memory panel. **Both windows stay non-focusable** (`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`), no full-screen transparent touch catcher or layout-no-limits flags. The panel clamps to available inset-adjusted display bounds, with scrollable content and reflow on configuration changes. Outside-window input can continue to the underlying app; within-window touches go to controls. No text editor/keyboard focus. Close hides the panel and restores the chip. **Android Back belongs to Minecraft**, so use the explicit Close/Stop buttons instead of assuming Back dismisses this non-focusable window.

The menu uses the real one-shot **MemoryClient** IPC and Copy report; it neither launches the game nor writes memory. Closing/stopping cancels the request, and there is no periodic snapshot poll. Current functionality remains read-only header/build-ID/mapped-segment inspection; no implemented gameplay module is implied.

This avoids an Activity switch merely to access controls and avoids deliberately taking window focus, but **cannot guarantee uninterrupted game ticking, zero performance cost, OEM support or compatibility with windows that block overlays**. Android still composites these external views. Original game rendering, touch/keyboard behavior and physical rotation require a phone test. Separate Controls/split-screen remains available without granting overlay permission.

## Tests and delivery gate

Five new Android regressions: bounded geometry/flags; actual denied-permission window refusal; real foreground service, repeated start, real overlay attachment/open/close, no-game memory IPC and immutable notification Stop cleanup; reflow and real AppOps revocation removing windows; and private UI-process service manifest identity. Emulator-only `UiAutomation` shell commands authorize/revoke overlay access and grant notifications for those tests; production uses Android Settings/runtime permission dialogs and never grants itself access. Existing tests remain enabled.

Android build/APK checks and the full Android 13 emulator suite passed for the corrected implementation below. No real Minecraft overlay compatibility or phone memory snapshot has yet been reported for this build. Keep the user-confirmed 0.5.4 and validated 0.6.0 references; original startup/engine pins, target Android13 and unchanged Minecraft requirement remain in effect.

### First Android result

Run 39996951448 compiled and passed APK checks, but the real-menu test checked `isAttachedToWindow()` in the same main-loop callback as `addView`. Android schedules attachment/traversal asynchronously; that assertion raced the first frame. The correction waits for actual attachment before retaining the original assertion and performing memory IPC. No test is skipped or replaced with a mock. That first artifact is not a deliverable; corrected full validation passed below.

## Validated overlay build and phone steps

[Android 13 CI run 39998204360](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39998204360) **passed**, job **120567826271**, **4m46s**, app/test **de5f7ea3a21e2fe5105164126194fa51f2208816**, host **0.6.1 / code14**. Build/APK exclusion checks and the full **59-case Android 13 x86_64 regression suite** passed, including all five new overlay tests. **27 local source/native guards** pass. No tests disabled or attachment/read assertions removed. The first failed artifact is not the deliverable. Downloaded report XML was not parsed locally; the successful Actions job is the validation source.

- [APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39998204360/artifacts/12520922597): **client-base-debug-92**, **6,248,463 bytes**. Install only **client-base-host-debug.apk**.
- [Device test reports](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39998204360/artifacts/12521052676): **client-base-device-tests-92**, **275,492 bytes**.
- Retained engine archive blob **c78f6e8642d334916d247487897eb871ad27c160**, original startup/SDK order and memory backend unchanged. The new optional host overlay permissions are intentional; no native game code is patched or packaged. PR#1 stays open. Keep the working 0.5.4 rollback and validated 0.6.0 reference.

1. Update only the host APK; leave Minecraft unchanged. Keep your working APK and preserve host reports before uninstalling only our host if debug signatures conflict.
2. Launcher → **Open client controls → Enable external overlay**. Accept the explanation, select Client Base in Android settings if needed, and allow display over other apps. Return to Controls and tap Enable again.
3. If asked for notifications, request/allow them, then tap Enable again. Alternatively explicitly start without notification permission; notification-shade Stop may then be hidden.
4. Return to the launcher and launch Bedrock, or return to an already-running game through Recents. Do not swipe away app tasks. The floating **ZIPS** chip should remain above it; drag to reposition, tap to open. This is a phone acceptance step, not an already-proven Minecraft result.
5. **Refresh memory snapshot**, then **Copy memory report**. Send that small report and whether opening/touching the menu pauses the game or interferes with input. Expected successful identity includes the game PID, `ELF64_HEADER_READ_OK` and the supplied engine build ID; an explicit unavailable/error report is useful too. No need to repeat bootstrap tests or produce another giant trace.
6. **Close** restores the chip; Android Back still belongs to the game. **Stop overlay** or notification Stop removes the overlay without intentionally stopping Bedrock. Separate Controls still works without any overlay grant.
