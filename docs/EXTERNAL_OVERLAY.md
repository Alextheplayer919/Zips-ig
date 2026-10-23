# 0.6.1 — optional external click GUI in Java

The user supplied a Compose overlay sketch, then authorized using our existing Java/native-view implementation style instead of Kotlin. This revises the earlier screen-only preference: **the separate Controls task remains the fallback; the new overlay is optional**. One APK, normal host UI process, fixed memory IPC to :game_host. No view or renderer is injected into Minecraft, no wrapper/lifecycle/SDK/native-memory backend changes. No fake module switches or Kotlin/Compose dependencies.

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

Android build/APK/emulator validation pending. Do not deliver until it passes. No real Minecraft overlay compatibility or phone memory snapshot has yet been reported for this build. Keep the user-confirmed 0.5.4 and validated 0.6.0 references; original startup/engine pins, target Android13 and unchanged Minecraft requirement remain in effect.
