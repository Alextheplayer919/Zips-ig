# Engine link test — user-reported phone result

## Evidence source

The user pasted the following probe output. Android 13 is the previously reported device environment. This is a manual report, not an independently captured device log or APK-manifest extraction. No new APK version/build-run identifier was supplied with this output.

```text
Probe PID: 18244
Probe UID: 11062
Installed Minecraft versionName: 1.26.50.4
versionCode: 972605004
APK paths: 1
Engine hash matched.
Probe PID: 18244
Probe UID: 11062
LINK_CHECK_SUCCESS (not game startup)
JNI_OnLoad: present
  owner: /data/data/dev.zips.clientbase.host/app_engine-input/libminecraftpe.so
  mapping base: 0x716809e000
ANativeActivity_onCreate: absent
ELF constructors ran. JNI_OnLoad/game entrypoints were NOT invoked.
```

## What passed as reported

- The imported, fingerprint-pinned engine matches the installed ARM64 engine's bytes. The reported installed package identifies that matching engine as **versionName 1.26.50.4 / versionCode 972605004**. This refines the user-stated target of **26.50**; it does not silently replace it with another version.
- `dlopen` with eager symbol resolution returned successfully in the host-owned private probe process, after constructors ran. This is runtime evidence beyond a static dependency list; it does not establish every optional library loaded later by the game is available.
- `JNI_OnLoad` resolved to the app-private imported library path, not a library in another app's process. The reported mapping base is an observation for this run only and must not become a hard-coded address.
- The probe did not find the default `ANativeActivity_onCreate` export. That does not identify the game's actual Java/native entry sequence, nor exclude an adapter/custom native entry point.

## What did not run

Our probe explicitly did not invoke the engine's `JNI_OnLoad` or game entrypoints. ELF constructors did execute and may have their own initialization effects. No claim is made about their internal behavior beyond the load returning.

There is no demonstrated game Application/Activity lifecycle, resource/context setup, rendering, title screen, sign-in, world loading or engine hook. The engine was linked in **`:engine_probe`**, not proven to run in the launcher Activity's process or the separately installed Minecraft process. The private process shares the host UID and is not a security sandbox.

## Next gate

The engine-link substep is complete at the user-reported level. [Gate 2](STATUS.md#gate-2--hosting-only-minecraft-adapter) remains open: understand the real Java/JNI bootstrap and prepare its prerequisites before an explicit JNI-initialization experiment. Do not blindly call a symbol just because it resolved, substitute dummy game classes, invoke the absent default NativeActivity entry point, or add gameplay hooks yet.
