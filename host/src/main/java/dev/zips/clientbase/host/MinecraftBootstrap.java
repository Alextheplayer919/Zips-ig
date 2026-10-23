package dev.zips.clientbase.host;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Controlled JVM-managed initialization only: no Activity object or lifecycle calls. */
final class MinecraftBootstrap {
    private static final String MAIN = "com.mojang.minecraftpe.MainActivity";
    private static final String PARENT = "com.google.androidgamesdk.GameActivity";
    private static final String BRIDGE = "dev.zips.clientbase.host.EngineNativeBridge";
    static final String KNOWN_CODE = "001a 6d20 011a a017 1071 3b0c 0001 011a d702 1071 3b0c 0001 0628 011a d703 2071 024f 0010 011a 3ccf 1071 3b0c 0001 011a 763f 1071 3b0c 0001 0628 011a e306 2071 024f 0010 001a b3e4 1071 3b0c 0000 001a d954 1071 3b0c 0000 000e";

    static void verifyMainInitializer(JSONObject main) throws IOException, JSONException {
        if (!main.getString("class").equals("Lcom/mojang/minecraftpe/MainActivity;")
                || !main.getString("superclass").equals("Lcom/google/androidgamesdk/GameActivity;")) {
            throw new IOException("Unexpected MainActivity/superclass; initialization refused");
        }
        JSONObject code = main.getJSONObject("clinit");
        if (code.getBoolean("truncated") || !code.getBoolean("reference_scan_complete")
                || code.getInt("code_units_total") != 45 || !KNOWN_CODE.equals(code.getString("code_units_hex"))) {
            throw new IOException("MainActivity initializer differs from the reported 1.26.50.4 build");
        }
        int[] units = {0, 2, 4, 7, 9, 13, 15, 18, 20, 23, 25, 29, 31, 34, 36, 39, 41};
        String load = "Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V";
        String log = "Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I";
        String omitted = "const-string: [non-library string omitted]";
        String[] expected = {omitted, "const-string: c++_shared", load, "const-string: maesdk", load,
                omitted, log, "const-string: HttpClient.Android", load, "const-string: PlayFabMultiplayer",
                load, omitted, log, "const-string: fmod", load, "const-string: minecraftpe", load};
        JSONArray refs = code.getJSONArray("references");
        if (refs.length() != units.length) throw new IOException("Unexpected initializer references");
        for (int i = 0; i < units.length; i++) {
            JSONObject ref = refs.getJSONObject(i);
            if (ref.getInt("unit") != units[i] || !expected[i].equals(ref.getString("reference"))) {
                throw new IOException("Unexpected initializer reference at position " + i);
            }
        }
    }

    static void verifyParentInitializer(JSONObject parent) throws IOException, JSONException {
        String blocked = "GameActivity has an unreviewed static initializer; saved its DEX report, no initialization attempted";
        if (!parent.getString("class").equals("Lcom/google/androidgamesdk/GameActivity;")
                || !parent.has("clinit")) throw new IOException(blocked);
        if (parent.isNull("clinit")) return;
        try {
            JSONObject code = parent.getJSONObject("clinit");
            // The phone's actual GameActivity has an explicit empty <clinit>:
            // zero registers and exactly DEX return-void (000e), with no references.
            // Do not generalize this to NOPs, longer code, missing or partial data.
            if (code.getInt("registers") == 0 && code.getInt("code_units_total") == 1
                    && "000e".equals(code.getString("code_units_hex"))
                    && !code.getBoolean("truncated") && code.getBoolean("reference_scan_complete")
                    && code.getJSONArray("references").length() == 0) return;
        } catch (JSONException malformed) {
            throw new IOException(blocked + " (incomplete initializer metadata)", malformed);
        }
        throw new IOException(blocked);
    }

    private static void verifySelectedEngine(String path, ApplicationInfo game, List<String> apks)
            throws IOException {
        if (path == null) throw new IOException("Guest loader cannot resolve minecraftpe");
        if (game.nativeLibraryDir != null) {
            File installed = new File(game.nativeLibraryDir, "libminecraftpe.so");
            if (installed.getCanonicalPath().equals(new File(path).getCanonicalPath())) {
                EngineInput.verifyLibrary(installed);
                return;
            }
        }
        for (String apk : apks) {
            if (!path.equals(apk + "!/lib/arm64-v8a/libminecraftpe.so")) continue;
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry entry = zip.getEntry("lib/arm64-v8a/libminecraftpe.so");
                if (entry == null || entry.getSize() != EngineInput.LIBRARY_BYTES) break;
                try (InputStream in = zip.getInputStream(entry)) {
                    if (EngineInput.LIBRARY_SHA.equals(EngineInput.sha256(in, EngineInput.LIBRARY_BYTES))) return;
                }
            }
        }
        throw new IOException("Selected loader engine is not the exact supplied fingerprint at an installed game path");
    }

    static String run(Context context, ApplicationInfo game, PackageInfo pkg, List<String> apks,
            Consumer<String> progress) throws Exception {
        if (!"1.26.50.4".equals(pkg.versionName) || pkg.getLongVersionCode() != 972605004L) {
            throw new IOException("This initialization experiment is restricted to reported Minecraft 1.26.50.4 / 972605004");
        }
        JSONObject main = DexBootstrapInspector.inspectApks(apks, "Lcom/mojang/minecraftpe/MainActivity;", context.getCacheDir());
        progress.accept("JAVA_BOOTSTRAP_PREFLIGHT\nActual MainActivity DEX:\n" + main.toString(2));
        verifyMainInitializer(main);
        JSONObject parent = DexBootstrapInspector.inspectApks(apks, "Lcom/google/androidgamesdk/GameActivity;", context.getCacheDir());
        String evidence = "MainActivity initializer matched reported 45-unit load sequence.\nActual GameActivity DEX:\n" + parent.toString(2);
        progress.accept("JAVA_BOOTSTRAP_PREFLIGHT\n" + evidence);
        verifyParentInitializer(parent);

        List<String> dex = new ArrayList<>();
        dex.add(context.getApplicationInfo().sourceDir);
        dex.addAll(apks);
        List<String> natives = new ArrayList<>();
        if (game.nativeLibraryDir != null) natives.add(game.nativeLibraryDir);
        for (String apk : apks) natives.add(apk + "!/lib/arm64-v8a");
        natives.add(context.getApplicationInfo().nativeLibraryDir);
        File optimized = new File(context.getCodeCacheDir(), "minecraft-bootstrap");
        if (!optimized.isDirectory() && !optimized.mkdirs()) throw new IOException("Cannot create bootstrap cache");
        List<String> lookups = new ArrayList<>();
        BootstrapClassLoader loader = new BootstrapClassLoader(String.join(File.pathSeparator, dex),
                optimized.getAbsolutePath(), String.join(File.pathSeparator, natives), context.getClassLoader(),
                (name, path) -> {
                    synchronized (lookups) {
                        if (lookups.size() < 32) lookups.add(name + " -> " + path);
                        progress.accept("JVM_INITIALIZATION_IN_PROGRESS\n" + evidence + "\nNative library lookups (not completion receipts):\n"
                                + lookupText(lookups));
                    }
                });
        String enginePath = loader.resolveNativeLibrary("minecraftpe");
        verifySelectedEngine(enginePath, game, apks);
        progress.accept("SELECTED_ENGINE_VERIFIED\n" + enginePath + "\n" + evidence);
        Class<?> mainClass = Class.forName(MAIN, false, loader);
        if (mainClass.getClassLoader() != loader || mainClass.getSuperclass().getClassLoader() != loader
                || !PARENT.equals(mainClass.getSuperclass().getName())) {
            throw new IOException("Real game classes are not owned by the intended loader");
        }
        progress.accept("ABOUT_TO_INITIALIZE_REAL_MAIN_ACTIVITY_CLASS\nNo Activity instance/lifecycle will be created.\n"
                + "Selected engine: " + enginePath + "\n" + evidence);
        // Original MainActivity calls System.loadLibrary itself. ART handles JNI_OnLoad
        // and native-library ownership. Never invoke an engine function pointer here.
        return onMainThread(() -> {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try {
                Class<?> initialized = Class.forName(MAIN, true, loader);
                if (initialized != mainClass) throw new IOException("Class identity changed during initialization");
                progress.accept("JAVA_CLASS_INITIALIZATION_RETURNED\nChecking already-mapped engine without loading it again.\n"
                        + evidence + "\nNative library lookups:\n" + lookupText(lookups));
                Class<?> bridge = Class.forName(BRIDGE, true, loader);
                String mapped = (String) bridge.getMethod("describeLoaded", String.class).invoke(null, enginePath);
                if (!mapped.startsWith("ENGINE_ALREADY_MAPPED")) throw new IOException(mapped);
                return "JVM_BOOTSTRAP_RETURNED (not game startup)\n"
                        + "Real MainActivity class initialization returned; JVM-managed native loads used the same guest loader.\n"
                        + "Supplied engine fingerprint verified at the loader-selected installed path; no import needed.\n"
                        + "JNI_OnLoad is mediated by ART, not manually called or separately instrumented by this probe.\n"
                        + mapped + "\nNative library lookups:\n" + lookupText(lookups) + "\n" + evidence
                        + "\nNo Minecraft Activity instance, Application/provider environment, lifecycle, surface or title screen created.";
            } finally { thread.setContextClassLoader(previous); }
        });
    }

    private static String lookupText(List<String> lookups) {
        synchronized (lookups) { return String.join("\n", lookups); }
    }

    static <T> T onMainThread(Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        if (!new Handler(Looper.getMainLooper()).post(task)) throw new IOException("Private main looper rejected bootstrap task");
        return task.get(); // Controller timeout kills this private process if initialization hangs.
    }

    static String describeFailure(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; error != null && i < 6; i++, error = error.getCause()) {
            if (i != 0) text.append("\nCaused by: ");
            String message = String.valueOf(error.getMessage());
            text.append(error.getClass().getName()).append(": ").append(message.substring(0, Math.min(message.length(), 2000)));
        }
        return text.toString();
    }
}
