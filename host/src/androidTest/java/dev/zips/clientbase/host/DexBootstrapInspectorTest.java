package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DexBootstrapInspectorTest {
    // This class lives only in the test APK. Inspection must never initialize it.
    public static final class MustNotInitialize {
        static { System.setProperty("clientbase.inspector.executed", "BAD"); }
        private static native void unused();
    }

    @Test public void readsRealGuestNativeMethodsAndLoadLibraryCall() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String apk = context.getPackageManager().getApplicationInfo("dev.zips.clientbase.guest", 0).sourceDir;
        JSONObject result = DexBootstrapInspector.inspectApks(Collections.singletonList(apk),
                "Ldev/zips/clientbase/guest/DemoGuestEntry;", context.getCacheDir());
        assertEquals(2, result.getInt("native_method_count"));
        assertFalse(result.getBoolean("native_methods_truncated"));
        JSONObject clinit = result.getJSONObject("clinit");
        assertTrue(clinit.toString(), clinit.getBoolean("reference_scan_complete"));
        assertTrue(clinit.toString(), references(clinit).contains("Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V"));
        assertTrue(clinit.toString(), references(clinit).contains("const-string: clientbase_demo"));
    }

    private static List<String> references(JSONObject clinit) throws JSONException {
        JSONArray array = clinit.getJSONArray("references");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getJSONObject(i).getString("reference"));
        return result;
    }

    @Test public void inspectionDoesNotExecuteStaticInitializer() throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        System.clearProperty("clientbase.inspector.executed");
        JSONObject result = DexBootstrapInspector.inspectApks(Collections.singletonList(tests.getApplicationInfo().sourceDir),
                "Ldev/zips/clientbase/host/DexBootstrapInspectorTest$MustNotInitialize;", host.getCacheDir());
        assertNotNull(result.getJSONObject("clinit"));
        assertEquals(1, result.getInt("native_method_count"));
        assertTrue(result.toString(), result.toString().contains("->setProperty("));
        assertFalse(result.toString().contains("clientbase.inspector.executed")); // Non-library literal redacted.
        assertNull(System.getProperty("clientbase.inspector.executed"));
    }

    @Test public void malformedHeadersAndTablesFailClosed() throws Exception {
        // Positive control: ensure malformed variants reach field/table validation,
        // rather than all failing because the fixture's buffer position is wrong.
        assertNull(new DexBootstrapInspector(header(56, 0)).inspect("Lmissing/Empty;"));
        for (ByteBuffer bytes : new ByteBuffer[] {ByteBuffer.allocate(4), header(56, Integer.MAX_VALUE),
                header(96, 1), header(40, 0), header(32, 100)}) {
            try { new DexBootstrapInspector(bytes); fail("Malformed DEX accepted"); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        }
    }

    private static ByteBuffer header(int offset, int value) {
        ByteBuffer b = ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
        b.put("dex\n035\0".getBytes(StandardCharsets.US_ASCII));
        b.putInt(32, 112); b.putInt(36, 112); b.putInt(40, 0x12345678);
        b.putInt(offset, value);
        b.position(0);
        return b;
    }

    @Test public void instructionWidthsHandlePayloadsAndUnknownOpcodes() {
        assertEquals(3, DexBootstrapInspector.width(new int[] {0x71, 0, 0}, 0));
        assertEquals(6, DexBootstrapInspector.width(new int[] {0x100, 1}, 0));
        assertEquals(10, DexBootstrapInspector.width(new int[] {0x200, 2}, 0));
        assertEquals(7, DexBootstrapInspector.width(new int[] {0x300, 2, 3, 0}, 0));
        assertEquals(-1, DexBootstrapInspector.width(new int[] {0x300}, 0));
        assertEquals(-1, DexBootstrapInspector.width(new int[] {0x73}, 0));
    }
}
