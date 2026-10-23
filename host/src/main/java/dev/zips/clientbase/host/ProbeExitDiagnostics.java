package dev.zips.clientbase.host;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Own-UID diagnostics only. Never query Minecraft's separate process or private files. */
final class ProbeExitDiagnostics {
    private static final int TRACE_LIMIT = 8 * 1024 * 1024;
    static final class Run {
        final int pid, uid;
        final long startedAt;
        final String cleanup, processSuffix;
        Run(int pid, int uid, long startedAt, String cleanup) { this(pid, uid, startedAt, cleanup, ":engine_probe"); }
        Run(int pid, int uid, long startedAt, String cleanup, String processSuffix) {
            if (!":engine_probe".equals(processSuffix) && !":game_host".equals(processSuffix)) throw new IllegalArgumentException("Unknown own process");
            this.pid = pid; this.uid = uid; this.startedAt = startedAt; this.cleanup = cleanup; this.processSuffix = processSuffix;
        }
    }
    private static File runFile(Context c) { return new File(c.getFilesDir(), "probe-exit-run.json"); }
    static File reportFile(Context c) { return new File(c.getFilesDir(), "probe-exit-last.txt"); }

    private static void write(File file, String text) throws IOException {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream out = null;
        try {
            out = atomic.startWrite(); out.write(text.getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(out);
        } catch (IOException error) { if (out != null) atomic.failWrite(out); throw error; }
    }
    private static void saveRun(Context c, Run run) throws Exception {
        write(runFile(c), new JSONObject().put("pid", run.pid).put("uid", run.uid)
                .put("started_at", run.startedAt).put("cleanup", run.cleanup).toString());
    }
    static void begin(Context c) throws Exception { saveRun(c, new Run(0, Process.myUid(), System.currentTimeMillis(), "none")); }
    static void recordPid(Context c, int pid) throws Exception {
        Run run = readRun(c);
        if (pid <= 0 || pid == Process.myPid() || run.uid != Process.myUid()) throw new IOException("Invalid own-child identity");
        saveRun(c, new Run(pid, run.uid, run.startedAt, run.cleanup));
    }
    static void cleanup(Context c, String note) throws Exception {
        Run run = readRun(c); saveRun(c, new Run(run.pid, run.uid, run.startedAt, note));
    }
    static Run readRun(Context c) throws Exception {
        File file = runFile(c);
        if (file.isFile()) {
            if (file.length() > 4096) throw new IOException("Oversized run metadata");
            JSONObject value = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            return new Run(value.getInt("pid"), value.getInt("uid"), value.getLong("started_at"), value.getString("cleanup"));
        }
        // Updating with the SAME signing key may preserve a pre-0.4.2 report.
        // Never accept a user-entered PID or a report from a different UID.
        File old = EngineProbeService.reportFile(c);
        if (old.isFile() && old.length() < 256 * 1024) {
            String text = new String(Files.readAllBytes(old.toPath()), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^Probe PID: ([0-9]+)\\s+Probe UID: ([0-9]+)").matcher(text);
            if (m.find() && Integer.parseInt(m.group(2)) == Process.myUid()) {
                return new Run(Integer.parseInt(m.group(1)), Process.myUid(), old.lastModified(),
                        "unknown (recovered old same-UID saved stage; cleanup history unavailable)");
            }
        }
        throw new IOException("No own-UID probe metadata. A changed debug key/uninstall loses old-UID history; run the new initialization attempt once.");
    }
    static String savedDisplay(Context c) throws Exception {
        File file = reportFile(c), stage = EngineProbeService.reportFile(c);
        if (!file.isFile() || file.length() > 512 * 1024 || file.lastModified() < stage.lastModified()) return null;
        if (file.lastModified() < readRun(c).startedAt) return null;
        return "Saved exit diagnostic (not a new run):\n" + new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    static void saveDisplay(Context c, String text) throws IOException { write(reportFile(c), text); }

    static boolean matches(Run run, int pid, int uid, String process, long timestamp, String packageName) {
        return run.pid > 0 && pid == run.pid && uid == run.uid && (":engine_probe".equals(run.processSuffix) ? (packageName + ":engine_probe").equals(process)
                    : (packageName + ":game_host").equals(process))
                && timestamp >= run.startedAt - 1000 && timestamp <= run.startedAt + 15 * 60 * 1000L;
    }
    static String collect(Context context) {
        try { return collect(context, readRun(context)); }
        catch (Exception error) { return "EXIT_DIAGNOSTIC_UNAVAILABLE\n" + error.getClass().getSimpleName() + ": " + error.getMessage(); }
    }
    static String collect(Context context, Run run) throws Exception {
        if (run.uid != Process.myUid() || run.pid <= 0 || run.pid == Process.myPid()) throw new IOException("Refusing non-own-child exit lookup");
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) throw new IOException("ActivityManager unavailable");
        ApplicationExitInfo found = null;
        for (int attempt = 0; attempt < 4 && found == null; attempt++) {
            if (attempt != 0) Thread.sleep(1000); // Android may publish history after Binder death.
            List<ApplicationExitInfo> history = manager.getHistoricalProcessExitReasons(context.getPackageName(), run.pid, 8);
            for (ApplicationExitInfo info : history) {
                if (matches(run, info.getPid(), info.getRealUid(), info.getProcessName(), info.getTimestamp(), context.getPackageName())) {
                    found = info; break;
                }
            }
        }
        StringBuilder out = new StringBuilder("ANDROID_OWN_PROCESS_EXIT_DIAGNOSTIC\n");
        out.append("Expected child PID: ").append(run.pid).append(" UID: ").append(run.uid).append('\n');
        out.append("Host cleanup note: ").append(run.cleanup).append('\n');
        out.append("Cleanup/unbind can produce SIGKILL; that alone does not identify the original failure.\n");
        if (found == null) out.append("No matching exit record yet (PID/UID/process-name/time filtered). Retry Collect last probe exit details; do not rerun initialization just for this.\n");
        else {
            out.append("Exit reason: ").append(reasonName(found.getReason())).append(" (").append(found.getReason()).append(")\n");
            out.append("Exit status/signal: ").append(found.getStatus()).append("\nExit timestamp (ms): ").append(found.getTimestamp()).append('\n');
            String description = String.valueOf(found.getDescription());
            out.append("Description: ").append(description.substring(0, Math.min(description.length(), 4096))).append('\n');
            if (Build.VERSION.SDK_INT >= 31 && found.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                try (InputStream trace = found.getTraceInputStream()) {
                    if (trace == null) out.append("Native tombstone unavailable; Android/OEM may omit it or publish it later.\n");
                    else {
                        byte[] bytes = readBounded(trace, TRACE_LIMIT + 1);
                        boolean truncated = bytes.length > TRACE_LIMIT;
                        if (truncated) bytes = java.util.Arrays.copyOf(bytes, TRACE_LIMIT);
                        out.append(NativeTombstoneSummary.decode(bytes, run.pid, run.uid, truncated));
                    }
                } catch (Exception error) { out.append("Native trace unavailable/unparsed: ").append(error.getMessage()).append('\n'); }
            } else out.append("No native protobuf trace requested for this reason/API.\n");
        }
        out.append("\nBest-effort own-PID logcat (may be restricted; review before sharing):\n").append(logcat(context, run));
        return out.length() <= 96 * 1024 ? out.toString() : out.substring(0, 96 * 1024) + "\n[diagnostic output truncated]";
    }
    static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "JAVA_CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "NATIVE_CRASH";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            default: return "OTHER_OR_UNKNOWN";
        }
    }
    private static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384];
        while (out.size() < limit) {
            int n = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
            if (n == -1) break;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    private static File fullLogFile(Context context, Run run) {
        return new File(context.getFilesDir(), run.processSuffix.equals(":game_host")
                ? "game-host-logcat-full.txt" : "probe-logcat-full.txt");
    }
    private static String logHeader(Run run) {
        return "FULL_AVAILABLE_OWN_PID_LOGCAT\nPID: " + run.pid + " UID: " + run.uid
                + " Started: " + run.startedAt + "\n";
    }
    static synchronized void appendFullLog(Context context, Run run, java.io.OutputStream output) throws IOException {
        if (run.uid != Process.myUid() || run.pid <= 0 || run.pid == Process.myPid())
            throw new IOException("Refusing non-own-child log export");
        AtomicFile file = new AtomicFile(fullLogFile(context, run));
        if (!file.getBaseFile().isFile()) {
            output.write("\nNo saved full log for this attempt; collect its exit first.\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream input = file.openRead()) {
            byte[] expected = logHeader(run).getBytes(StandardCharsets.UTF_8);
            byte[] actual = new byte[expected.length]; int offset = 0;
            while (offset < actual.length) {
                int n = input.read(actual, offset, actual.length - offset);
                if (n == -1) break;
                offset += n;
            }
            if (offset != expected.length || !java.util.Arrays.equals(expected, actual)) {
                output.write("\nSaved full log belongs to a different attempt; not exported.\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            output.write("\n--- Full available log (not limited to the UI preview) ---\n".getBytes(StandardCharsets.UTF_8));
            output.write(expected);
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1;) output.write(buffer, 0, n);
        }
    }
    static synchronized String summarizeGame(Context context, Run run, String stages, String details) {
        // Reuse the identity-checked full stream, not just its small on-screen tail.
        try {
            File file = fullLogFile(context, run);
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new AtomicFile(file).openRead(), StandardCharsets.UTF_8))) {
                if (!("FULL_AVAILABLE_OWN_PID_LOGCAT").equals(reader.readLine())
                        || !("PID: " + run.pid + " UID: " + run.uid + " Started: " + run.startedAt).equals(reader.readLine()))
                    throw new IOException("Different log attempt");
                return GameFailureSummary.summarize(stages, details, reader);
            }
        } catch (IOException error) {
            try { return GameFailureSummary.summarize(stages, details, new java.io.StringReader("")); }
            catch (IOException impossible) { return "Failure summary unavailable"; }
        }
    }
    private static String logcat(Context context, Run run) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-d", "-b", "main", "-b", "crash", "--pid=" + run.pid,
                    "-T", String.format(java.util.Locale.ROOT, "%d.%03d", run.startedAt / 1000, run.startedAt % 1000),
                    "-v", "threadtime", "*:W").redirectErrorStream(true).start();
            InputStream input = process.getInputStream();
            FutureTask<String> task = new FutureTask<>(() -> {
                synchronized (ProbeExitDiagnostics.class) {
                AtomicFile file = new AtomicFile(fullLogFile(context, run));
                FileOutputStream output = null;
                try {
                    output = file.startWrite(); output.write(logHeader(run).getBytes(StandardCharsets.UTF_8));
                    String preview = FullLogCapture.copy(input, output);
                    file.finishWrite(output);
                    return preview.isEmpty() ? "No accessible matching lines.\n" : preview;
                } catch (Exception error) {
                    if (output != null) file.failWrite(output);
                    throw error;
                } finally { input.close(); }
                }
            });
            Thread reader = new Thread(task, "own-probe-logcat"); reader.setDaemon(true); reader.start();
            try { return task.get(2500, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException timeout) {
                // No byte cap. A stalled subprocess must not hang collection indefinitely.
                process.destroyForcibly();
                return "[Logcat collection timed out; available output may be partial]\n"
                        + task.get(1000, TimeUnit.MILLISECONDS);
            }
        } catch (Exception error) { return "Unavailable: " + error.getClass().getSimpleName() + "\n"; }
        finally { if (process != null) process.destroyForcibly(); }
    }
}
