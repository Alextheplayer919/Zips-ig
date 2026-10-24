package dev.zips.clientbase.host;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Small fixtures only: CI never imports or executes the ARM64 game engine. */
@RunWith(AndroidJUnit4.class)
public final class EngineProbeTest {
    @Test public void wrongFingerprintIsRejected() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean existed = EngineInput.library(context).exists();
        try {
            EngineInput.importStream(context, new ByteArrayInputStream(new byte[] {1, 2, 3}));
            fail("Unexpected input accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Not the supplied"));
        }
        assertEquals(existed, EngineInput.library(context).exists());
    }

    @Test public void missingLibraryReportsLinkerFailure() {
        String result = EngineNativeBridge.run("/does-not-exist/libclientbase_missing.so");
        assertTrue(result, result.contains("LINK_CHECK_FAILED"));
        assertTrue(result, result.contains("libclientbase_missing.so"));
    }

    @Test public void ownNativeLibraryCanBeLinkedWithoutEngineInitialization() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File own = new File(context.getApplicationInfo().nativeLibraryDir, "libclientbase_engineprobe.so");
        String result = EngineNativeBridge.run(own.getAbsolutePath());
        assertTrue(result, result.contains("LINK_CHECK_SUCCESS"));
        assertTrue(result, result.contains("JNI_OnLoad/game entrypoints were NOT invoked"));
    }
}
