package dev.zips.clientbase.host;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Application-context binding survives UI rotation; callbacks never retain an Activity. */
public final class EngineProbeController {
    public interface Listener { void engineStatus(String text); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Listener> listener = new WeakReference<>(null);
    private static Context app;
    private static boolean running;
    private static boolean collecting;
    private static int diagnosticId;
    private static boolean bound;
    private static int runId;
    private static int requestMode;
    private static int probePid;
    private static long finishedAt;
    private static String latest;
    private EngineProbeController() {}

    public static void attach(Context context, Listener current) {
        app = context.getApplicationContext();
        listener = new WeakReference<>(current);
        if (latest == null) latest = lastReport();
        current.engineStatus(latest);
    }

    public static void detach(Listener current) {
        if (listener.get() == current) listener.clear();
    }

    public static boolean isRunning() { return running || collecting; }

    public static void show(String text) {
        MAIN.post(() -> {
            latest = text;
            Listener current = listener.get();
            if (current != null) current.engineStatus(text);
        });
    }

    private static String lastReport() {
        if (app != null) {
            try {
                String saved = ProbeExitDiagnostics.savedDisplay(app);
                if (saved != null) return saved;
            } catch (Exception ignored) { /* Fall back to saved stage. */ }
        }
        return savedStage();
    }

    private static String savedStage() {
        if (app != null && EngineProbeService.reportFile(app).isFile()) {
            try {
                return "Saved last probe stage (not a new run):\n"
                        + new String(Files.readAllBytes(EngineProbeService.reportFile(app).toPath()), StandardCharsets.UTF_8);
            } catch (IOException ignored) { /* Show initial instructions instead. */ }
        }
        return "Inspect installed Minecraft bootstrap without running game code. "
                + "Engine import is needed only for the optional link probe.";
    }

    private static final Runnable TIMEOUT = () -> finish("PROBE_TIMEOUT\nLast saved stage:\n" + savedStage(), true, true);
    private static final Messenger RECEIVER = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (!running || message.getData().getInt("id") != runId) return true;
        if (message.getData().getInt("uid", -1) == android.os.Process.myUid()) {
            int pid = message.getData().getInt("pid", 0);
            if (pid > 0 && pid != android.os.Process.myPid() && probePid != pid) {
                probePid = pid;
                try { ProbeExitDiagnostics.recordPid(app, pid); }
                catch (Exception error) { show("Could not persist child identity: " + error.getMessage()); }
            }
        }
        String text = message.getData().getString("text", "Empty probe response");
        if (message.getData().getBoolean("done")) finish(text);
        else show(text);
        return true;
    }));
    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!running) return; // Never auto-rerun initialization after a child death.
            try {
                Message request = Message.obtain();
                request.what = requestMode;
                request.getData().putInt("id", runId);
                request.replyTo = RECEIVER;
                new Messenger(binder).send(request);
            } catch (android.os.RemoteException error) {
                finish("PROBE_IPC_FAILED\n" + error.getMessage());
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (running) finish("Probe process disconnected before completion. Native failure, OS termination "
                    + "or another interruption is possible.\n" + savedStage(), true, false);
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { finish("Probe service returned no binding."); }
    };

    public static void start() { start(1); }

    public static void inspectBootstrap() { start(2); }

    public static void initializeMinecraft() { start(3); }

    private static void start(int mode) {
        if (app == null || running || collecting || GameHostController.busy()) return;
        if (android.os.SystemClock.uptimeMillis() - finishedAt < 2000) {
            show("Wait two seconds for the previous probe process to exit.");
            return;
        }
        if (EngineInput.IMPORTING.get()) { show("Wait for the engine import to complete."); return; }
        try { ProbeExitDiagnostics.begin(app); }
        catch (Exception error) { show("Cannot save probe identity for exit diagnostics: " + error.getMessage()); return; }
        probePid = 0;
        requestMode = mode;
        running = true;
        runId++;
        show(mode == 2 ? "Reading installed Minecraft DEX in the private process. No guest code will run."
                : mode == 3 ? "Preparing JVM-managed Minecraft initialization in a fresh private process. No Activity will be created."
                : "Starting private probe process. Native constructors may crash that process; no game startup is attempted.");
        try {
            bound = app.bindService(new Intent(app, EngineProbeService.class), CONNECTION, Context.BIND_AUTO_CREATE);
            if (!bound) finish("Could not bind the private probe service.");
            else MAIN.postDelayed(TIMEOUT, 180000);
        } catch (RuntimeException error) { finish("Probe binding failed: " + error.getMessage()); }
    }

    private static void finish(String text) { finish(text, false, true); }

    private static void finish(String text, boolean diagnose, boolean killChild) {
        running = false;
        finishedAt = android.os.SystemClock.uptimeMillis();
        MAIN.removeCallbacks(TIMEOUT);
        try {
            ProbeExitDiagnostics.cleanup(app, diagnose
                    ? (killChild ? "controller timeout: cleanup/SIGKILL requested" : "unbind after disconnect; no direct UI SIGKILL, service cleanup may run if still alive")
                    : "normal terminal result: unbind/child cleanup requested");
        } catch (Exception ignored) { /* Collection reports unavailable metadata explicitly. */ }
        if (bound) {
            bound = false;
            app.unbindService(CONNECTION); // Service destroys only its own private process.
        }
        // JNI initialization runs on the child's main looper. If it hangs, that
        // looper cannot dispatch Service.onDestroy; terminate the reported own-UID
        // child directly instead. Never target the UI process or a remembered PID.
        if (killChild && requestMode == 3 && probePid > 0 && probePid != android.os.Process.myPid()) {
            android.os.Process.killProcess(probePid);
        }
        probePid = 0;
        if (diagnose) collectExit(text);
        else show(text);
    }

    public static void collectLastExitDetails() {
        if (app == null || running || collecting || GameHostController.busy()) return;
        collectExit("Last saved probe stage:\n" + savedStage());
    }

    private static void collectExit(String base) {
        collecting = true;
        int generation = ++diagnosticId;
        int expectedRun = runId;
        show(base + "\n\nCollecting Android own-process exit details...");
        Runnable expired = () -> {
            if (!collecting || diagnosticId != generation || runId != expectedRun) return;
            collecting = false;
            publishDiagnostic(base + "\n\nEXIT_DIAGNOSTIC_TIMEOUT: Android did not return details in 12 seconds. "
                    + "Use Collect last probe exit details later; no automatic initialization retry.");
        };
        MAIN.postDelayed(expired, 12000);
        Thread worker = new Thread(() -> {
            String result = ProbeExitDiagnostics.collect(app);
            MAIN.post(() -> {
                if (!collecting || diagnosticId != generation || runId != expectedRun) return;
                collecting = false;
                MAIN.removeCallbacks(expired);
                publishDiagnostic(base + "\n\n" + result);
            });
        }, "probe-exit-diagnostic");
        worker.setDaemon(true);
        worker.start();
    }

    private static void publishDiagnostic(String text) {
        try { ProbeExitDiagnostics.saveDisplay(app, text); }
        catch (IOException error) { text += "\nCould not save exit diagnostic: " + error.getMessage(); }
        show(text);
    }
}
