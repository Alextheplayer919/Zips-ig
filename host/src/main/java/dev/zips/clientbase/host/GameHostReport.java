package dev.zips.clientbase.host;

import android.content.Context;
import android.os.Process;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

final class GameHostReport {
    private static final List<String> events = new ArrayList<>();
    static File stageFile(Context c) { return new File(c.getFilesDir(), "game-host-last.txt"); }
    static String compactStage(String event) {
        if (event.startsWith("JVM_INITIALIZATION_IN_PROGRESS\n")) {
            int marker = event.indexOf("Native library lookups (not completion receipts):\n");
            if (marker >= 0) {
                String[] lines = event.substring(marker).trim().split("\n");
                return "NATIVE_LIBRARY_LOOKUP (not load completion)\n" + lines[lines.length - 1];
            }
        }
        if (event.startsWith("JAVA_BOOTSTRAP_PREFLIGHT\n")) return "JAVA_BOOTSTRAP_PREFLIGHT: inspecting original initializer metadata (validation follows)";
        if (event.startsWith("SELECTED_ENGINE_VERIFIED\n")) {
            String[] lines = event.split("\n", 3);
            return lines[0] + (lines.length > 1 ? "\n" + lines[1] : "");
        }
        return event;
    }
    static synchronized String stage(Context c, String event) {
        event = compactStage(event);
        String trimmed = event.length() > 2200 ? event.substring(0, 160) + "\n...\n" + event.substring(event.length() - 2000) : event;
        if (events.size() == 32) events.remove(0);
        events.add(trimmed);
        String text = "BEDROCK_ACTIVITY_ATTEMPT (not title-screen proof)\nGame PID: " + Process.myPid()
                + "\nGame UID: " + Process.myUid() + "\n" + String.join("\n---\n", events);
        try { write(stageFile(c), text); } catch (Exception ignored) { text += "\nCould not persist stage"; }
        return text;
    }
    static void write(File file, String text) throws Exception {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream out = null;
        try { out = atomic.startWrite(); out.write(text.getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(out); }
        catch (Exception e) { if (out != null) atomic.failWrite(out); throw e; }
    }
    static String read(File file) {
        try {
            if (!file.isFile() || file.length() > 256 * 1024) return "No bounded saved game report";
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) { return "Report unavailable: " + e.getClass().getSimpleName(); }
    }
}
