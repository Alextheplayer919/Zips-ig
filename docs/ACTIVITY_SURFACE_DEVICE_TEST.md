# Original Activity resumed and received a Surface; abort evidence truncated

Source: user-requested inspection of `idk.txt` on GitHub main, blob `eb5994381f75b400459ee0d7f0629e93aea06354`, 27,720 bytes. Associated with delivered host 0.5.1 by conversation context; the report does not print a host version. Raw report kept only in ignored cache, not committed: includes device/session identifiers and SDK configuration/API-key values.

**PID 27870 / UID 11077**, original supplied startup/engine pins passed. Original onCreate, onStart and onResume returned. Production observer recorded **SURFACE_CREATED**, **SURFACE_CHANGED 2400x1080**, followed by another returned onResume. Engine logs record JNI_OnLoad completion, android_main, graphics-context acquisition and **MinecraftGame::init && MinecraftGame::setSize!**. The previous host nested-class exception is no longer present; progress past its failure site is positively evidenced. No title-screen/visible game output is established by this file alone.

Android exit history: **SIGNALED (2), signal 6 / SIGABRT**, timestamp **1792769989497**, cleanup **none**. No abort message/backtrace was captured. The old collector read the **first 24 KiB**, reaching its cap around 17:39:47.058, before the exit at 17:39:49.497. SIGNALED was not treated as a native-tombstone record. Swappy's in-memory-loader lookup warning persists, but graphics initialization subsequently proceeds; do not label it the abort cause. No engine reimport is indicated.

## Requested change: no log export size limit

Host **0.5.2 / code 10** removes the app-imposed byte cap from the **saved own-PID logcat and TXT file export**. Read the completed logcat snapshot to EOF, streaming directly into app-private AtomicFile storage instead of constructing an unlimited String in RAM. The UI retains a small **tail** preview, with an explicit pointer to the full saved log. Save exports the report plus the complete saved available log, streaming again without the preview/report-size cap. Share-text is labelled as a preview; use Save for full output.

Keep process/UID/attempt identity checks and original logcat start-time filtering. Full logs for probe and Activity attempts have separate filenames; mismatched attempt headers are not exported. File transactions/export are serialized. No Android storage/log-reading permission is added, no whole-device logging or proprietary game code changes.

Android/OEM log buffers can already have overwritten records; permissions may hide records; disk/provider errors may prevent a save. The collector retains its subprocess timeout to avoid hanging the app and labels a timed-out capture as possibly partial. This removes our **size truncation**, not those platform/I/O constraints. Native protobuf decoding, metadata and UI parsers remain independently bounded. Old truncated bytes cannot be reconstructed from the saved TXT; a no-game-execution collect may recover them only if still in Android's buffer.

Regression tests cover output larger than the old log **and** UI report limits, a late fatal marker, byte-for-byte saved data, bounded tail preview, empty/UTF-8 data and rejection of another attempt's saved full log. Android validation pending; do not deliver this change before checks pass.
