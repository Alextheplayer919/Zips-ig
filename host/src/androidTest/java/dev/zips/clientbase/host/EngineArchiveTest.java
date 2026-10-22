package dev.zips.clientbase.host;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** CI supplies the exact archive via run-as. Import ARM64 data; never execute it. */
@RunWith(AndroidJUnit4.class)
public final class EngineArchiveTest {
    @Test public void suppliedArchiveExtractsAndVerifiesOnAndroid() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixture = new File(context.getFilesDir(), "engine-test.7z");
        assumeTrue("Optional exact archive not provisioned", fixture.isFile());
        assertFalse("Do not overwrite an existing user import in this test", EngineInput.library(context).exists());
        try (FileInputStream input = new FileInputStream(fixture)) {
            File library = EngineInput.importStream(context, input);
            assertEquals(EngineInput.LIBRARY_BYTES, library.length());
            EngineInput.verifyLibrary(library);
        } finally {
            if (EngineInput.library(context).exists()) assertTrue(EngineInput.library(context).delete());
            assertTrue(fixture.delete());
        }
    }
}
