# Host 0.4.2 — diagnose the initialization disconnect

## Latest phone result

The collector returned useful real phone evidence: Android native crash/SIGABRT, pending `ACCESS_NETWORK_STATE` SecurityException from Xbox network observer initialization, and PlayFab JNI frames. [Evidence and host 0.4.3 correction](NETWORK_PERMISSION_DEVICE_TEST.md). Earlier uncertainty below documents what was known before that report; the cause of this particular abort is no longer just a last-lookup guess. A subsequent [phone report](JVM_BOOTSTRAP_DEVICE_TEST.md) now shows successful returned class initialization with the engine already mapped; Activity/title-screen startup remains untested.

## Reported failure, not an inferred culprit

The user reports host 0.4.1 losing its private process during real JVM initialization: PID **18392**, UID **11068**, Minecraft **1.26.50.4 / 972605004**, one APK and supplied engine hash matched. MainActivity matched and GameActivity's exact `000e` no-op gate passed. [Normalized record](JVM_DISCONNECT_DEVICE_TEST.json) refers to the already recorded identical GameActivity metadata and omits installation-specific random path components.

Last recorded native-library lookup order: `c++_shared`, `maesdk`, `HttpClient.Android`, **`PlayFabMultiplayer`**, all from the installed game's ARM64 native directory. These callbacks occur **before** the JVM load returns. They are not success receipts; PlayFab is not proven to be the cause. No subsequent fmod/minecraftpe lookup or completed JVM bootstrap was recorded. Native abort/crash, process exit, OS termination or another interruption remain possible. No exit reason, abort message or backtrace was available in that report.

The no-op policy correction worked. There is no evidence yet to justify changing load order, skipping PlayFab/JNI initialization, patching code or inventing an Activity environment.

## What 0.4.2 adds (initialization sequence unchanged)

- Before each run, save current host UID, start wall time and subsequently the child PID from the private service's own IPC response.
- After an unexpected disconnect, collect **`ActivityManager.getHistoricalProcessExitReasons`** for **our package and exact child PID**. Accept only the same real UID, exact `:engine_probe` process name, and timestamp within the run window. Never query the separately installed Minecraft process or its private files.
- Retry record publication briefly off the UI thread, then report unavailable explicitly. A manual **Collect last probe exit details (no game execution)** button retries collection without restarting Minecraft initialization. There is no automatic initialization retry; a late service reconnect is ignored unless a run is active.
- Display the system reason, status/signal, timestamp and bounded description. A cleanup request is labelled: normal service cleanup or a controller timeout may itself cause SIGKILL, which alone is not evidence of the original failure. The UI no longer directly SIGKILLs an unexpectedly disconnected child; it still unbinds, and service cleanup can run if the process survived. Timeout/normal-result cleanup remains available.
- For native-crash records on API 31+, try **`ApplicationExitInfo.getTraceInputStream`**. Decode selected fields from Android's native tombstone protobuf: matching PID/UID, crashing TID, signal, abort message and up to **32 crashing-thread frames**, including ELF-relative PCs, file/function names and build IDs. Mismatched/unidentified traces are withheld. Partial/unsupported/truncated data is labelled.
- Parser input is capped at **8 MiB**, field traversal at 100,000 fields, individual strings at 4,096 bytes. Raw trace data is not saved/shared; registers, stack memory, memory maps, embedded log buffers, file descriptors and other threads are omitted from formatted output. Selected field numbers follow [AOSP's tombstone schema as mirrored here](https://github.com/omnirom/android_system_core/blob/508fa8ce13c5306747ad391fbd9455e782faa0fd/debuggerd/proto/tombstone.proto). No generated protobuf runtime is added.
- Best-effort logcat is restricted to the reported **own child PID**, since the recorded start time, with only the app's normal log access. It is capped at **24 KiB** with a 2.5-second reader timeout; no READ_LOGS, root, wireless ADB or other new permission is requested. Android/OEM restrictions may return no useful logs. Logs are supporting evidence, not authoritative exit attribution.
- Formatted diagnostic output is capped at 96 Ki characters and saved separately from the service stage. The UI stops waiting after 12 seconds; stale collector callbacks cannot replace a newer run/report. These caps and unknown fields can limit diagnostic completeness.

Abort messages/logcat can contain app-provided values. **Review the report before sharing.** The extractor is not a general privacy sanitizer. No raw memory/register dump or full-device log is exported.

## Installing versus recovering the old failure

If an update preserves the **same signing key/UID**, the collector can recover the old PID/UID from our own saved stage and query matching history. If the debug signing key changes and Android requires uninstalling the host, its data and old-UID diagnostic access are lost. The new build cannot promise to recover the earlier crash after that.

Install host **0.4.2** only; never uninstall Minecraft. If old history survives, tap **Collect last probe exit details** first. Otherwise perform **one** new **Initialize Minecraft Java / JNI (no Activity)** attempt with the existing consent. After a disconnect, leave the host open for automatic diagnostic collection, then share the full report. If history/trace was not ready, use the collection button again **without rerunning initialization**.

No engine re-import or demo/link test is needed. The loader/order/gates and exact engine identity remain unchanged. This is diagnosis of a failed initialization, not a claim of JNI success, an Activity lifecycle implementation or a title screen.

## Tests and evidence boundary

New Android cases validate selected-field protobuf decoding with a **synthetic** fixture, omission of synthetic memory/register strings, PID/UID mismatch rejection, partial/malformed handling, and exact run identity/time filtering. An emulator test binds our private service **without sending any probe request**, intentionally SIGKILLs that own-UID process, and checks Android's real matching exit record. It does not crash/load Minecraft, and its SIGKILL must not be labelled a native crash.

A successful emulator test is not evidence that this phone/OEM will supply a tombstone or that its native-crash format is identical. Actual Android build/test results and the next phone exit report must be recorded separately. No load-order fix is claimed before that evidence exists.

### Validated host 0.4.2 build

App/test commit **`2ac0b627eb1369c2824661fe783ebea3744455f2`**, host **0.4.2 / code 6**, passed compilation and Android 13/API 33 x86_64 integration in [run 39931616080](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39931616080), job **120341631453**, **4m58s**. All eighteen instrumentation cases are retained, including four new diagnostic cases. The real own-child termination test found a matching Android exit record and did not call it a native crash. The native tombstone parser was tested with synthetic data, not a real phone tombstone. Seventeen local source/native checks pass; twenty Java files syntax-parse.

- [Current APK ZIP: client-base-debug-41](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39931616080/artifacts/12491479101) — **3,473,285 bytes**, install only `client-base-host-debug.apk`.
- [Device reports: client-base-device-tests-41](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39931616080/artifacts/12491957614) — **173,498 bytes**.

This verifies the implemented diagnostics on the emulator, not crash attribution on the user's phone. Original Minecraft initialization still has not returned successfully as reported. No archive/game library/initializer/load-order changes were made in this correction.
