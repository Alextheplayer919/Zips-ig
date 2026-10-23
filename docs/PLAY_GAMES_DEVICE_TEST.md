# Play Games missing initialization: confirmed JNI abort prerequisite

## Phone evidence

User-pasted fatal excerpt (no new device PID/UID or complete report attached; do not assume it is the preceding PID 27870 attempt):

```text
JNI DETECTED ERROR IN APPLICATION:
JNI CallVoidMethodV called with pending exception java.lang.IllegalStateException:
PlayGamesSdk has not been initialized.
Ensure that PlayGamesSdk.initialize() has been called.
at Preconditions.checkState (play-services-basement 18.9.0)
at com.google.android.gms.internal.games_v2.zzbw.zzb (play-services-games-v2 21.0.0)
at PlayGames.getAchievementsClient(Activity)
native: libminecraftpe.so relative PC 0x0853fbc0
native: art::CheckJNI::CallVoidMethodV
native: art::JavaVMExt::JniAbortV
Exit: SIGNALED (2), signal 6 / SIGABRT
```

The missing SDK initialization is the concrete prerequisite failure. JNI continued with that Java exception pending and ART aborted. This is not merely a last-library-lookup hypothesis. The Swappy lookup, vendor EGL/performance warnings and cached device-id message in the same excerpt are not this fatal stack's cause. Device identifier and Breakpad session/dump identifiers are not copied into this document. Keep CheckJNI enabled, do not clear/swallow the exception, skip achievements, patch native code or bypass authentication/entitlement.

## Inspection of existing supplied files (no new file needed)

All methods below are in the already reviewed/pinned startup input, inspected statically with Androguard; original game/SDK code was not executed locally.

- Supplied manifest declares **com.google.android.gms.games.provider.PlayGamesInitProvider**, non-exported, authority under the original game package, initOrder **99**.
- In **classes.dex**, provider `onCreate()` obtains its Context and calls `com.google.android.gms.internal.games_v2.zzbw.zza(Context)` (if Context missing, its log explicitly asks for manual PlayGamesSdk.initialize).
- Public **PlayGamesSdk.initialize(Context)** calls that **same `zzbw.zza(Context)`** directly and returns void. No provider emulation is required for this initialization call.
- `zzbw.zza` obtains/casts the actual Application context, constructs/stores SDK state, starts its helpers and registers Application lifecycle callbacks. Only on return does it set the boolean checked by `zzbw.zzb()`.
- `zzbw.zzb()` checks that boolean with exactly the exception message seen on the phone.
- Inspected helper registration in `games.internal.zzf.zzb()` uses `Application.registerActivityLifecycleCallbacks`. This must precede original Activity attachment/lifecycle, not be invoked after a JNI crash.

We intentionally did not register original providers. Therefore the original Play Games provider's initialization never happened in our own process; its class merely existing in DEX is insufficient.

## Host 0.5.3 / code 11 correction

After existing startup/engine pins and preparation, **on the main thread in the private :game_host process**, call the **original public PlayGamesSdk.initialize(Context)** through the prepared game loader, using the actual host Application. This happens before `Class.forName(WRAPPER, true, loader)`, original class initialization/construction and Android attachment/lifecycle. The SDK sees the same Application on which Android dispatches callbacks. Original resource/classloader views are already installed; package name/UID/storage/permissions stay genuinely the host's.

Check SDK class ownership and exact public static void initialize(Context) signature before invoking. Retain/restore the thread context loader in finally. Log entered/returned/blocked stages, propagate failures and block launch if startup fails; never emit a false returned receipt. Do not initialize a parent-loader copy, add a Google SDK dependency to the host, install synthetic provider authorities or run every provider speculatively.

Tests exercise the actual invocation code using **our own named demo initializer**, not a fake Google/Minecraft class: main thread, real Application identity, guest class/thread-loader identity, successful return, failure propagation/CCL restoration, missing/wrong-owner/wrong-signature rejection and worker-thread refusal. Source guards check ordering before original class initialization and preservation of input gates/process restriction.

This recreates one verified missing startup effect. **Initialization is not Play Games sign-in, achievement success, original-package/signing registration, provider parity or a hosted title screen.** Host package identity, SDK resolution Activities, account/services and other SDK/provider requirements may still fail legitimately. No further input file is currently needed.

Android compilation/regression validation pending; do not deliver until it passes.
