package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import java.io.StringReader;
import org.junit.Test;

public final class GameFailureSummaryTest {
    private final String stages = "ORIGINAL_ON_RESUME_RETURNED\nSURFACE_CREATED";
    @Test public void signalAndSwappyWarningDoNotInventCause() throws Exception {
        String result = GameFailureSummary.summarize(stages,
                "Exit reason: SIGNALED (2)\nExit status/signal: 6\nHost cleanup note: none",
                new StringReader("10-23 17:39:46.191 123 123 E SwappyDisplayManager: couldn't find libminecraftpe.so\n"));
        assertTrue(result, result.contains("SIGABRT"));
        assertTrue(result, result.contains("cause unknown"));
        assertTrue(result, result.contains("Affected area: Not identified"));
        assertTrue(result, result.contains("graphics Surface created"));
    }
    @Test public void firstFatalFrameIdentifiesAreaNotWrapperLowerInStack() throws Exception {
        String result = GameFailureSummary.summarize(stages, "Exit reason: JAVA_CRASH (4)", new StringReader(
                "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: java.lang.IllegalStateException: fixture\n"
                + "E AndroidRuntime: at com.mojang.minecraftpe.MainActivity.onResume(MainActivity.java:10)\n"
                + "E AndroidRuntime: at dev.zips.clientbase.host.MinecraftHostedActivity.onResume(MinecraftHostedActivity.java:40)\n"));
        assertTrue(result, result.contains("Original Bedrock Java/JNI path"));
        assertFalse(result, result.contains("Affected area: Host adapter"));
        assertTrue(result, result.contains("java.lang.IllegalStateException: fixture"));
    }
    @Test public void unavailableExitAndExplicitCleanupRemainDistinctFromCrash() throws Exception {
        String result = GameFailureSummary.summarize("GAME_ENVIRONMENT_PREPARED", "", new StringReader(""));
        assertTrue(result, result.contains("failure not established"));
        result = GameFailureSummary.summarize(stages,
                "Exit reason: SIGNALED (2)\nExit status/signal: 9\nHost cleanup note: User requested termination", new StringReader(""));
        assertTrue(result, result.contains("User requested termination"));
        assertFalse(result, result.contains("SIGABRT"));
    }
}
