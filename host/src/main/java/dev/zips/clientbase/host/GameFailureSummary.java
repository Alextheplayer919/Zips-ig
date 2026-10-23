package dev.zips.clientbase.host;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/** Evidence summary, not a guess based on the last warning or library lookup. */
final class GameFailureSummary {
    static String summarize(String stages, String exit, Reader log) throws IOException {
        String progress = stages.contains("SURFACE_CREATED") ? "Graphics Surface created"
                : stages.contains("ORIGINAL_ON_RESUME_RETURNED") ? "Original Activity resumed"
                : stages.contains("ORIGINAL_ON_START_RETURNED") ? "Original Activity started"
                : stages.contains("ORIGINAL_ON_CREATE_RETURNED") ? "Original onCreate returned"
                : stages.contains("ORIGINAL_ON_CREATE_ENTERED") ? "Inside original onCreate"
                : stages.contains("ACTIVITY_ATTACHED") ? "Activity attached"
                : stages.contains("GAME_ENVIRONMENT_PREPARED") ? "Game environment prepared" : "Preparation / class loading";
        if (stages.contains("SURFACE_CREATED") && stages.contains("ORIGINAL_ON_RESUME_RETURNED"))
            progress = "Original Activity resumed; graphics Surface created (not title-screen proof)";
        String reason = field(exit, "Exit reason: "), signal = field(exit, "Exit status/signal: ");
        String cleanup = field(exit, "Host cleanup note: ");
        String evidence = "", firstFrame = ""; boolean javaFatal = false; int warnings = 0, errors = 0;
        BufferedReader reader = new BufferedReader(log);
        for (String line; (line = reader.readLine()) != null;) {
            if (line.matches(".*\\sW\\s.*")) warnings++;
            if (line.matches(".*\\s[EF]\\s.*")) errors++;
            if (line.contains("FATAL EXCEPTION:")) { javaFatal = true; evidence = concise(line); firstFrame = ""; }
            else if (line.contains("Abort message:") || line.contains("JNI DETECTED ERROR IN APPLICATION"))
                evidence = concise(line);
            else if (javaFatal && line.contains("AndroidRuntime:") && !line.contains("Process:")
                    && !line.contains("at ") && evidence.contains("FATAL EXCEPTION:")) evidence = concise(line);
            if (javaFatal && firstFrame.isEmpty() && line.contains("AndroidRuntime:") && line.contains("at "))
                firstFrame = line.substring(line.indexOf("at ") + 3).trim();
        }
        String failure = reason.isEmpty() ? "No matching exit record; failure not established"
                : reason + (signal.isEmpty() ? "" : "; status/signal " + signal + (signal.equals("6") ? " (SIGABRT)" : ""));
        if (reason.isEmpty() && !evidence.isEmpty()) failure = "Exit record unavailable; explicit failure diagnostic captured";
        String area = "Not identified; last successful stage is not necessarily the failing subsystem";
        if (firstFrame.startsWith("dev.zips.clientbase.host.")) area = "Host adapter (first Java failure frame)";
        else if (firstFrame.startsWith("com.mojang.")) area = "Original Bedrock Java/JNI path (first Java failure frame)";
        else if (firstFrame.startsWith("com.google.androidgamesdk.")) area = "Android GameActivity SDK (first Java failure frame)";
        else if (!firstFrame.isEmpty()) area = concise(firstFrame) + " (first Java failure frame, not root-cause proof)";
        if (evidence.contains("JNI DETECTED ERROR IN APPLICATION")) area = "ART / JNI boundary (explicit runtime diagnostic)";
        return "WHAT WENT WRONG\nLast successful stage: " + progress + "\nObserved exit: " + failure
                + "\nAffected area: " + area
                + "\nFailure evidence: " + (evidence.isEmpty() ? "No explicit fatal exception/abort message captured; cause unknown" : evidence)
                + "\nConfidence: " + (evidence.isEmpty() ? "Cause unconfirmed; do not attribute warnings as the cause"
                        : "Explicit failure diagnostic captured; stack location is not root-cause proof")
                + "\nHost cleanup: " + (cleanup.isEmpty() ? "unknown" : cleanup)
                + "\nWarnings/errors: " + warnings + "/" + errors + " captured lines; warnings alone do not establish the cause"
                + "\nScope: available own-process warning/error/fatal snapshot; Android may restrict or overwrite records.\n";
    }
    private static String field(String text, String key) {
        for (String line : text.split("\n")) if (line.startsWith(key)) return line.substring(key.length()).trim();
        return "";
    }
    private static String concise(String text) {
        return text.length() > 400 ? text.substring(0, 400) + "…" : text;
    }
}
