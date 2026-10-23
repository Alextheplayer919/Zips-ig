package dev.zips.clientbase.host;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.AtomicFile;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** One-shot private app process. This is crash separation, NOT a security sandbox. */
public final class EngineProbeService extends Service {
    private static final String GAME = "com.mojang.minecraftpe";
    private static final String BRIDGE = "dev.zips.clientbase.host.EngineNativeBridge";
    private final AtomicBoolean started = new AtomicBoolean();
    private Messenger client;
    private int runId;
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if ((message.what == 1 || message.what == 2) && started.compareAndSet(false, true)) {
            client = message.replyTo;
            runId = message.getData().getInt("id");
            boolean inspectOnly = message.what == 2;
            new Thread(() -> probe(inspectOnly), "engine-probe").start();
        }
        return true;
    }));

    public static File reportFile(android.content.Context context) {
        return new File(context.getFilesDir(), "engine-probe-last.txt");
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private void report(String text, boolean done) {
        String full = "Probe PID: " + Process.myPid() + "\nProbe UID: " + Process.myUid() + "\n" + text;
        AtomicFile file = new AtomicFile(reportFile(this));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(full.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException error) {
            if (stream != null) file.failWrite(stream);
            full += "\nCould not save report: " + error.getClass().getSimpleName();
        }
        Message response = Message.obtain();
        Bundle data = new Bundle();
        data.putInt("id", runId);
        data.putString("text", full);
        data.putBoolean("done", done);
        response.setData(data);
        try { if (client != null) client.send(response); }
        catch (RemoteException ignored) { /* Controller can read the saved last stage. */ }
    }

    private void probe(boolean inspectOnly) {
        try {
            report(inspectOnly ? "Checking installed engine fingerprint; no game code will be executed."
                    : "Checking imported engine and process ABI; no engine code executed yet.", false);
            String arch = System.getProperty("os.arch", "unknown");
            if (!inspectOnly && (!Process.is64Bit() || !(arch.equals("aarch64") || arch.equals("arm64")))) {
                throw new IOException("ARM64 engine cannot run in this process (" + arch + ").");
            }
            File engine = EngineInput.library(this);
            if (!inspectOnly) EngineInput.verifyLibrary(engine);
            PackageManager pm = getPackageManager();
            ApplicationInfo game = pm.getApplicationInfo(GAME, 0);
            PackageInfo pkg = pm.getPackageInfo(GAME, 0);
            List<String> apks = new ArrayList<>();
            apks.add(game.sourceDir);
            if (game.splitSourceDirs != null) {
                for (String split : game.splitSourceDirs) apks.add(split);
            }
            String version = "Installed Minecraft versionName: " + pkg.versionName
                    + "\nversionCode: " + pkg.getLongVersionCode() + "\nAPK paths: " + apks.size();
            report(version + "\nVerifying installed engine matches the supplied fingerprint...", false);
            if (!matchesInstalledEngine(game, apks)) {
                throw new IOException(version + "\nInstalled ARM64 engine is absent or does not match the supplied file. "
                        + "Refusing to mix native dependencies across unknown game builds.");
            }
            if (inspectOnly) {
                report(version + "\nEngine hash matched. Reading MainActivity DEX as data only...", false);
                org.json.JSONObject bootstrap = DexBootstrapInspector.inspectApks(apks,
                        "Lcom/mojang/minecraftpe/MainActivity;", getCacheDir());
                report(version + "\nEngine hash matched.\nBOOTSTRAP_INSPECTION_COMPLETE\n"
                        + bootstrap.toString(2) + "\nNo guest class was defined/initialized; no dlopen, JNI_OnLoad "
                        + "or game entrypoint was invoked. Reachable initializers and lifecycle remain untested.", true);
                return;
            }
            List<String> nativePaths = new ArrayList<>();
            nativePaths.add(getApplicationInfo().nativeLibraryDir);
            if (game.nativeLibraryDir != null) nativePaths.add(game.nativeLibraryDir);
            for (String apk : apks) nativePaths.add(apk + "!/lib/arm64-v8a");
            File optimized = new File(getCodeCacheDir(), "engine-probe-dex");
            if (!optimized.isDirectory() && !optimized.mkdirs()) throw new IOException("Cannot create probe cache.");
            // Define only our bridge in this loader. Its linker namespace can see the
            // matching game's native paths; no Minecraft Java class is instantiated.
            DexClassLoader loader = new DexClassLoader(getApplicationInfo().sourceDir,
                    optimized.getAbsolutePath(), String.join(File.pathSeparator, nativePaths), getClassLoader()) {
                @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
                        throws ClassNotFoundException {
                    if (!BRIDGE.equals(name)) return super.loadClass(name, resolve);
                    Class<?> found = findLoadedClass(name);
                    if (found == null) found = findClass(name);
                    if (resolve) resolveClass(found);
                    return found;
                }
            };
            report(version + "\nEngine hash matches. Entering dlopen; ELF constructors may now run. "
                    + "JNI_OnLoad/game entrypoints will NOT be invoked.", false);
            Object result = Class.forName(BRIDGE, true, loader).getMethod("run", String.class)
                    .invoke(null, engine.getAbsolutePath());
            report(version + "\nEngine hash matched.\n" + result, true);
        } catch (Exception | LinkageError error) {
            report("PROBE_BLOCKED\n" + error.getClass().getSimpleName() + ": " + error.getMessage(), true);
        }
    }

    private static boolean matchesInstalledEngine(ApplicationInfo game, List<String> apks) throws IOException {
        if (game.nativeLibraryDir != null) {
            File extracted = new File(game.nativeLibraryDir, "libminecraftpe.so");
            if (extracted.isFile() && extracted.length() == EngineInput.LIBRARY_BYTES) {
                try (InputStream in = new FileInputStream(extracted)) {
                    if (EngineInput.LIBRARY_SHA.equals(EngineInput.sha256(in, EngineInput.LIBRARY_BYTES))) return true;
                }
            }
        }
        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry entry = zip.getEntry("lib/arm64-v8a/libminecraftpe.so");
                if (entry == null || entry.getSize() != EngineInput.LIBRARY_BYTES) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    if (EngineInput.LIBRARY_SHA.equals(EngineInput.sha256(in, EngineInput.LIBRARY_BYTES))) return true;
                }
            }
        }
        return false;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        // Terminate the one-shot native environment; do not invent an engine shutdown.
        Process.killProcess(Process.myPid());
    }
}
