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
    private static boolean bound;
    private static int runId;
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

    public static boolean isRunning() { return running; }

    public static void show(String text) {
        MAIN.post(() -> {
            latest = text;
            Listener current = listener.get();
            if (current != null) current.engineStatus(text);
        });
    }

    private static String lastReport() {
        if (app != null && EngineProbeService.reportFile(app).isFile()) {
            try {
                return "Saved last probe stage (not a new run):\n"
                        + new String(Files.readAllBytes(EngineProbeService.reportFile(app).toPath()), StandardCharsets.UTF_8);
            } catch (IOException ignored) { /* Show initial instructions instead. */ }
        }
        return "Import the exact supplied libminecraftpe.7z (or extracted .so), then run the optional link probe.";
    }

    private static final Runnable TIMEOUT = () -> finish("PROBE_TIMEOUT\nLast saved stage:\n" + lastReport());
    private static final Messenger RECEIVER = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (!running || message.getData().getInt("id") != runId) return true;
        String text = message.getData().getString("text", "Empty probe response");
        if (message.getData().getBoolean("done")) finish(text);
        else show(text);
        return true;
    }));
    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                Message request = Message.obtain();
                request.what = 1;
                request.getData().putInt("id", runId);
                request.replyTo = RECEIVER;
                new Messenger(binder).send(request);
            } catch (android.os.RemoteException error) {
                finish("PROBE_IPC_FAILED\n" + error.getMessage());
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (running) finish("Probe process disconnected before completion. Native failure, OS termination "
                    + "or another interruption is possible.\n" + lastReport());
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { finish("Probe service returned no binding."); }
    };

    public static void start() {
        if (app == null || running) return;
        if (android.os.SystemClock.uptimeMillis() - finishedAt < 2000) {
            show("Wait two seconds for the previous probe process to exit.");
            return;
        }
        if (EngineInput.IMPORTING.get()) { show("Wait for the engine import to complete."); return; }
        running = true;
        runId++;
        show("Starting private probe process. Native constructors may crash that process; no game startup is attempted.");
        try {
            bound = app.bindService(new Intent(app, EngineProbeService.class), CONNECTION, Context.BIND_AUTO_CREATE);
            if (!bound) finish("Could not bind the private probe service.");
            else MAIN.postDelayed(TIMEOUT, 180000);
        } catch (RuntimeException error) { finish("Probe binding failed: " + error.getMessage()); }
    }

    private static void finish(String text) {
        running = false;
        finishedAt = android.os.SystemClock.uptimeMillis();
        MAIN.removeCallbacks(TIMEOUT);
        if (bound) {
            bound = false;
            app.unbindService(CONNECTION); // Service destroys only its own private process.
        }
        show(text);
    }
}
