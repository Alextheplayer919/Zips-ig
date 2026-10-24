# Original Activity resumed and received a Surface; abort evidence truncated

Source: user-requested inspection of `idk.txt` on GitHub main, blob `eb5994381f75b400459ee0d7f0629e93aea06354`, 27,720 bytes. Associated with delivered host 0.5.1 by conversation context; the report does not print a host version. Raw report kept only in ignored cache, not committed: includes device/session identifiers and SDK configuration/API-key values.

**PID 27870 / UID 11077**, original supplied startup/engine pins passed. Original onCreate, onStart and onResume returned. Production observer recorded **SURFACE_CREATED**, **SURFACE_CHANGED 2400x1080**, followed by another returned onResume. Engine logs record JNI_OnLoad completion, android_main, graphics-context acquisition and **MinecraftGame::init && MinecraftGame::setSize!**. The previous host nested-class exception is no longer present; progress past its failure site is positively evidenced. No title-screen/visible game output is established by this file alone.

Android exit history: **SIGNALED (2), signal 6 / SIGABRT**, timestamp **1792769989497**, cleanup **none**. No abort message/backtrace was captured. The old collector read the **first 24 KiB**, reaching its cap around 17:39:47.058, before the exit at 17:39:49.497. SIGNALED was not treated as a native-tombstone record. Swappy's in-memory-loader lookup warning persists, but graphics initialization subsequently proceeds; do not label it the abort cause. No engine reimport is indicated.

## Requested change: no log export size limit

Host **0.5.2 / code 10** removes the app-imposed byte cap from the **saved own-PID logcat and TXT file export**. Read the completed logcat snapshot to EOF, streaming directly into app-private AtomicFile storage instead of constructing an unlimited String in RAM. The UI retains a small **tail** preview, with an explicit pointer to the full saved log. Save exports the report plus the complete saved available log, streaming again without the preview/report-size cap. Share-text is labelled as a preview; use Save for full output.

Keep process/UID/attempt identity checks and original logcat start-time filtering. Full logs for probe and Activity attempts have separate filenames; mismatched attempt headers are not exported. File transactions/export are serialized. No Android storage/log-reading permission is added, no whole-device logging or proprietary game code changes.

Android/OEM log buffers can already have overwritten records; permissions may hide records; disk/provider errors may prevent a save. The collector retains its subprocess timeout to avoid hanging the app and labels a timed-out capture as possibly partial. This removes our **size truncation**, not those platform/I/O constraints. Native protobuf decoding, metadata and UI parsers remain independently bounded. Old truncated bytes cannot be reconstructed from the saved TXT; a no-game-execution collect may recover them only if still in Android's buffer.

Regression tests cover output larger than the old log **and** UI report limits, a late fatal marker, byte-for-byte saved data, bounded tail preview, empty/UTF-8 data and rejection of another attempt's saved full log. Android validation passed for the combined revised warning/error/fatal collection and summary below.

## Revised request: useful failures instead of everything

The user then preferred warnings/errors/fatal records plus an explanation. Collection now applies logcat `*:W` (W/E/F), retaining lifecycle milestones separately. This changes the collected snapshot, not the original engine logging code. The saved filtered log still has no app-imposed export byte cap. A **WHAT WENT WRONG** summary precedes the timeline: last known stage, observed exit/signal, explicit fatal message if available, affected area from the first fatal Java frame or explicit JNI diagnostic, confidence, and host cleanup. Warnings alone never identify the culprit; missing evidence stays **cause unknown**. This is diagnostic triage, not automatic root-cause certainty. It cannot recover data already omitted by the old collector.


## Validated focused-diagnostics build

[Android 13 CI run 39982356765](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39982356765) passed for app/test commit **a004f454055c19f5ad8e5778e76a7072f40e5d6c**, job **120513350930**, **5m32s**. Host **0.5.2 / code 10**; build, APK exclusion checks and the **39-case Android 13 x86_64 suite** passed. Retained 33 tests plus 3 file-stream/export tests and 3 summary tests. Tests include large output, late fatal marker, byte identity, UTF-8/empty data, attempt separation, unknown SIGABRT with unrelated Swappy error, first-frame attribution rather than a lower wrapper frame, unavailable exit and explicit cleanup. Twenty-three local source/native guards pass. No original ARM64 game execution or saved-TXT phone capture is claimed by CI.

- [APK ZIP](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39982356765/artifacts/12513099454): `client-base-debug-74`, 6,200,296 bytes. Install only `client-base-host-debug.apk`.
- [Reports](https://github.com/Alextheplayer919/Zips-ig/actions/runs/39982356765/artifacts/12513933285): `client-base-device-tests-74`, 222,238 bytes. Passing CI conclusion recorded; independent local XML parsing not claimed.
- Earlier a7d7cfe build 39981565622 failed dependency resolution with Maven HTTP **429**, before compilation. The passing combined build above supersedes it; no dependency checks bypassed or tests disabled.

After updating the host without removing its data/UID, try **Collect last game exit (no game execution)** and then **Save Bedrock Activity report (.txt)**. Old data can be recovered only if Android retains it under the same UID; an uninstall or overwritten buffer prevents that. Do not relaunch merely to collect history. No engine import, demo/link/bootstrap repeat, Minecraft change or new input file is required by this diagnostic change. Original abort cause and title screen remain unresolved.
