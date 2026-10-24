# Confirmed initialization abort: missing host network-state permission

## Later corrected phone result

The subsequent [Bedrock bootstrap report](JVM_BOOTSTRAP_DEVICE_TEST.md), PID 12037 / UID 11072, returned class initialization normally with the hash-matched installed engine already mapped. This completes the bootstrap substep as reported, not Activity/title-screen hosting. The failed attempt below remains historical evidence; no repeat of the successful test is needed.

## Phone evidence

The user supplied host 0.4.2's Android exit diagnostic for **PID 31985 / UID 11070**, Minecraft **1.26.50.4 / 972605004**, one APK and matched engine hash. This is user-provided system diagnostic output, not independent device access. [Normalized selected evidence](NETWORK_PERMISSION_DEVICE_TEST.json) omits installation-specific path components and retains the relevant exception and frames.

Android reported **`NATIVE_CRASH (5)`**, **`SIGABRT (6)`**, signal code **`-1 / SI_QUEUE`**, crashing TID **31985**. The key abort text is:

```text
JNI DETECTED ERROR IN APPLICATION: JNI FindClass called with pending exception
java.lang.SecurityException: ConnectivityService: Neither user 11070 nor current
process has android.permission.ACCESS_NETWORK_STATE.
```

The Java exception passes through `ConnectivityManager.registerNetworkCallback` and **`com.xbox.httpclient.NetworkObserver.Initialize` (line 72)** during native loading from the real MainActivity initializer. The native stack reaches **`libPlayFabMultiplayer.so` `AndroidJniHelper::Init`**, then **`JNI_OnLoad`**. ART's checked JNI layer aborts when `FindClass` is called while that Java exception is pending.

This identifies a missing **host** permission and the resulting JNI failure chain. It is not evidence of a bad engine hash, missing PlayFab file or a need to skip/reorder original loads. The installed game's separate UID/permissions do not transfer to a host process loading its code. Earlier reports only had a last lookup, so they could not establish this cause; this diagnostic provides the additional evidence.

The logcat line `JNI_OnLoad success` during `libforcedarkimpl.so` startup is an OEM/framework event, **not a successful Minecraft JNI result**. The abort message is truncated, native frames are capped at 32 and the pasted logcat ends mid-line. No title screen or completed Minecraft engine initialization is established.

## Minimal correction: host 0.4.3

- Declare **only `android.permission.ACCESS_NETWORK_STATE`** in the host manifest. This is a normal, install-time-granted Android permission, with no runtime permission dialog. Demo/contract modules request no permissions.
- Check the permission before defining or initializing game classes. If absent, return a readable `PROBE_BLOCKED` error instead of entering JNI with that known missing prerequisite.
- Report `HOST_NETWORK_STATE_PERMISSION_GRANTED` before continuing the existing preflight. Keep the load order, exact game/version/hash/initializer checks, original classes, JVM native-library ownership and exit diagnostics unchanged.
- Keep CheckJNI enabled; do not clear/ignore a pending SDK exception, patch native code or bypass Android permission enforcement.

Network-state access is a deliberate addition to the host's capabilities and is also available to trusted guest code running under that UID. It does **not** grant Internet socket access, location, microphone, storage, root or broad log access. `INTERNET` and other permissions are **not added speculatively** in this patch. A later different requirement must be reported separately, not assumed solved.

## Regression validation and next phone step

Android tests check the normal permission's actual grant to the host, rejection of a denied-context preflight, and the same public `registerNetworkCallback(NetworkRequest, NetworkCallback)` overload shown in the crash. The test unregisters the callback and requires no network arrival or outbound traffic. It uses our own context only, not Minecraft code. Local guards allowlist exactly this host permission and enforce that its preflight precedes guest class loading/initialization.

After the corrected APK is validated, install host **0.4.3** only, keep Minecraft unchanged, tap **Initialize Minecraft Java / JNI (no Activity)** once and share the result. No archive import, demo/link or static-only test is needed. If it disconnects again, leave the host open for exit collection and use **Collect last probe exit details** to retry diagnostics without rerunning game code.

This fixes the evidenced missing prerequisite, not a claim that all later SDK initialization, lifecycle or title-screen requirements are complete. A new phone result is still required.

## Corrected build validation

[Android 13 CI run 39959687156](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39959687156) passed compilation and the emulator integration suite for app/test commit `9a1d0a20b6c5f1c0ed84ff73b0dea9820b8fcda5`, job `120436542251` (4m19s). The suite includes the three new permission tests above and retains existing archive/hash, own-JNI, initializer-policy and exit-diagnostic coverage. Eighteen local source/native guards pass. The ARM64 Minecraft engine is not executed by these x86_64 emulator tests; actual corrected phone initialization remains pending.

- [Host 0.4.3 APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39959687156/artifacts/12505205745): `client-base-debug-44`, 3,474,533 bytes. Install only `client-base-host-debug.apk`.
- [Android test reports](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39959687156/artifacts/12504317965): `client-base-device-tests-44`, 193,776 bytes. CI reports success; downloading the report ZIP into this sandbox hit the artifact storage host's EOF/network restriction, so no separate local XML inspection is claimed.

The supplied archive's Git blob remains `c78f6e8642d334916d247487897eb871ad27c160`. PR #1 stays open.
