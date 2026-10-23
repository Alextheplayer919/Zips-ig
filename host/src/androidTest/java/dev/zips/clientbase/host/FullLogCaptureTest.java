package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FullLogCaptureTest {
    @Test public void exportKeepsAllBytesAndPreviewKeepsFatalAfterLargeStartup() throws Exception {
        String log = "startup noise\n".repeat(25000) + "FATAL late abort evidence\n";
        byte[] bytes = log.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String preview = FullLogCapture.copy(new ByteArrayInputStream(bytes), output);
        assertArrayEquals(bytes, output.toByteArray());
        assertTrue(preview, preview.endsWith("FATAL late abort evidence\n"));
        assertTrue(preview.length() < FullLogCapture.PREVIEW_BYTES + 200);
        assertTrue(preview.contains("full available log saved"));
    }
    @Test public void emptyAndShortLogsAreNotAltered() throws Exception {
        for (String log : new String[] {"", "Short log\nUTF8: žščť\n"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertEquals(log, FullLogCapture.copy(new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)), output));
            assertEquals(log, output.toString("UTF-8"));
        }
    }
    @Test public void savedLogExportRejectsOtherAttemptAndPreservesLargeValidFile() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Use probe storage, never overwrite the current game attempt's report.
        java.io.File file = new java.io.File(context.getFilesDir(), "probe-logcat-full.txt");
        ProbeExitDiagnostics.Run run = new ProbeExitDiagnostics.Run(12345, Process.myUid(), 123, "test");
        String header = "FULL_AVAILABLE_OWN_PID_LOGCAT\nPID: 12345 UID: " + Process.myUid() + " Started: 123\n";
        String body = "fixture\n".repeat(50000) + "last abort\n";
        try {
            java.nio.file.Files.write(file.toPath(), (header + body).getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ProbeExitDiagnostics.appendFullLog(context, run, output);
            assertTrue(output.toString("UTF-8").endsWith(header + body));
            output.reset();
            ProbeExitDiagnostics.appendFullLog(context,
                    new ProbeExitDiagnostics.Run(12345, Process.myUid(), 124, "different attempt"), output);
            assertTrue(output.toString("UTF-8").contains("different attempt"));
            assertFalse(output.toString("UTF-8").contains("last abort"));
        } finally { file.delete(); }
    }
}
