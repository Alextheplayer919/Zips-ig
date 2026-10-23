package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Only our own bridge runs in CI, never Minecraft or a fake Minecraft class. */
@RunWith(AndroidJUnit4.class)
public final class MinecraftBootstrapTest {
    @Test public void childLoaderOwnsJvmLibraryAndJniCallbackRunsOnce() throws Exception {
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = Files.createTempDirectory(host.getCodeCacheDir().toPath(), "jvm-fixture-").toFile();
        File library = new File(directory, "libclientbase_engineprobe.so");
        File unloaded = new File(directory, "libnotloaded_fixture.so");
        File original = new File(host.getApplicationInfo().nativeLibraryDir, library.getName());
        Files.copy(original.toPath(), library.toPath());
        Files.copy(original.toPath(), unloaded.toPath());
        assertTrue(library.setReadOnly()); assertTrue(unloaded.setReadOnly());
        try {
            BootstrapClassLoader loader = new BootstrapClassLoader(host.getApplicationInfo().sourceDir,
                    directory.getAbsolutePath(), directory.getAbsolutePath(), host.getClassLoader(), (name, path) -> {});
            assertEquals(library.getCanonicalPath(), new File(loader.resolveNativeLibrary("clientbase_engineprobe")).getCanonicalPath());
            String name = "dev.zips.clientbase.host.EngineNativeBridge";
            Class<?> bridge = Class.forName(name, false, loader);
            assertSame(loader, bridge.getClassLoader());
            assertNotSame(EngineNativeBridge.class, bridge);
            assertSame(bridge, MinecraftBootstrap.onMainThread(() -> {
                assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
                return Class.forName(name, true, loader);
            }));
            assertEquals(1, bridge.getMethod("initializationCount").invoke(null));
            assertSame(bridge, Class.forName(name, true, loader));
            assertEquals(1, bridge.getMethod("initializationCount").invoke(null));
            String mapped = (String) bridge.getMethod("describeLoaded", String.class).invoke(null, library.getAbsolutePath());
            assertTrue(mapped, mapped.startsWith("ENGINE_ALREADY_MAPPED"));
            assertTrue(mapped, mapped.contains("JNI_OnLoad owner:"));
            String absent = (String) bridge.getMethod("describeLoaded", String.class).invoke(null, unloaded.getAbsolutePath());
            assertTrue(absent, absent.startsWith("ENGINE_MAPPING_NOT_CONFIRMED"));
        } finally {
            Files.deleteIfExists(library.toPath()); Files.deleteIfExists(unloaded.toPath()); Files.deleteIfExists(directory.toPath());
        }
    }

    @Test public void reportedInitializerAcceptedButChangedCodeOrReferencesRejected() throws Exception {
        JSONObject main = reportedMain();
        MinecraftBootstrap.verifyMainInitializer(main);
        JSONObject changedCode = reportedMain();
        changedCode.getJSONObject("clinit").put("code_units_hex", "000e");
        expectMainBlocked(changedCode);
        JSONObject changedReference = reportedMain();
        changedReference.getJSONObject("clinit").getJSONArray("references").getJSONObject(16)
                .put("reference", "Lfake/Loader;->load()V");
        expectMainBlocked(changedReference);
        JSONObject truncated = reportedMain(); truncated.getJSONObject("clinit").put("truncated", true);
        expectMainBlocked(truncated);
    }

    @Test public void unreviewedGameActivityInitializerIsBlockedBeforeExecution() throws Exception {
        JSONObject parent = new JSONObject().put("class", "Lcom/google/androidgamesdk/GameActivity;")
                .put("clinit", JSONObject.NULL);
        MinecraftBootstrap.verifyParentInitializer(parent);
        parent.put("clinit", new JSONObject());
        try { MinecraftBootstrap.verifyParentInitializer(parent); fail("Unknown superclass initializer accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("unreviewed")); }
    }

    private static void expectMainBlocked(JSONObject main) throws Exception {
        try { MinecraftBootstrap.verifyMainInitializer(main); fail("Unknown initializer accepted"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }

    private static JSONObject reportedMain() throws Exception {
        String load = "Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V";
        String log = "Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I";
        String omitted = "const-string: [non-library string omitted]";
        int[] units = {0, 2, 4, 7, 9, 13, 15, 18, 20, 23, 25, 29, 31, 34, 36, 39, 41};
        String[] values = {omitted, "const-string: c++_shared", load, "const-string: maesdk", load,
                omitted, log, "const-string: HttpClient.Android", load, "const-string: PlayFabMultiplayer", load,
                omitted, log, "const-string: fmod", load, "const-string: minecraftpe", load};
        JSONArray refs = new JSONArray();
        for (int i = 0; i < units.length; i++) refs.put(new JSONObject().put("unit", units[i]).put("reference", values[i]));
        JSONObject code = new JSONObject().put("code_units_hex", MinecraftBootstrap.KNOWN_CODE).put("code_units_total", 45)
                .put("truncated", false).put("reference_scan_complete", true).put("references", refs);
        return new JSONObject().put("class", "Lcom/mojang/minecraftpe/MainActivity;")
                .put("superclass", "Lcom/google/androidgamesdk/GameActivity;").put("clinit", code);
    }
}
