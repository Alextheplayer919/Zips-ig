package dev.zips.clientbase.host;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.ResultReceiver;
import java.io.File;
import java.lang.ref.WeakReference;
import org.json.JSONObject;

/** Single explicit attempt, no crash/relaunch loop. Binding and identity survive UI rotation. */
public final class GameHostController {
    public interface Listener { void gameStatus(String text); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static WeakReference<Listener> listener = new WeakReference<>(null);
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static boolean sessionActive, serviceBound, prepareSent, ready, launched, collecting;
    private static int generation, pid, collectionId;
    private static long startedAt;
    private static IBinder binder;
    private static ServiceConnection connection;
    private static String latest;

    private GameHostController() {}

    private static File runFile() { return new File(app.getFilesDir(), "game-host-run.json"); }
    private static File exitFile() { return new File(app.getFilesDir(), "game-host-exit.txt"); }

    public static void attach(Context c, Listener l) {
        app = c.getApplicationContext();
        listener = new WeakReference<>(l);
        if (latest == null) latest = "No Activity attempt yet. The completed bootstrap need not be repeated.";
        l.gameStatus(latest);
    }

    public static void detach(Listener l) { if (listener.get() == l) listener.clear(); }
    public static void resumed(Activity a) { foreground = new WeakReference<>(a); refresh(); launchIfReady(); }
    public static void paused(Activity a) { if (foreground.get() == a) foreground.clear(); }
    public static boolean busy() { return sessionActive || collecting; }

    private static void show(String text) {
        latest = text;
        Listener l = listener.get();
        if (l != null) l.gameStatus(text);
    }

    public static String current() { return latest == null ? "No game report" : latest; }

    static void writeFullReport(java.io.OutputStream output, String snapshot) throws Exception {
        output.write(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!runFile().isFile()) return;
        if (runFile().length() > 4096) throw new java.io.IOException("Oversized run metadata");
        JSONObject run = new JSONObject(new String(java.nio.file.Files.readAllBytes(runFile().toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        ProbeExitDiagnostics.appendFullLog(app, new ProbeExitDiagnostics.Run(run.getInt("pid"),
                run.getInt("uid"), run.getLong("started_at"), run.optString("cleanup", "unknown"), ":game_host"), output);
    }

    private static void saveRun(String cleanup) {
        try {
            GameHostReport.write(runFile(), new JSONObject().put("pid", pid).put("uid", Process.myUid())
                    .put("started_at", startedAt).put("cleanup", cleanup).toString());
        } catch (Exception e) {
            show("Could not persist game-run identity: " + e.getMessage());
        }
    }

    public static void start() {
        if (app == null || busy() || EngineProbeController.isRunning()) return;
        ++generation;
        final int attempt = generation;
        pid = 0;
        startedAt = System.currentTimeMillis();
        prepareSent = false;
        ready = false;
        launched = false;
        sessionActive = true;
        saveRun("none");
        try {
            GameHostReport.write(GameHostReport.stageFile(app),
                    "New explicitly requested game attempt; no game code executed yet.");
        } catch (Exception ignored) {}
        show("Preparing original Bedrock Activity in a separate host-owned process...");
        try {
            connection = connectionFor(attempt);
            serviceBound = app.bindService(new Intent(app, GameHostService.class), connection, Context.BIND_AUTO_CREATE);
            if (serviceBound) {
                MAIN.postDelayed(TIMEOUT, 180000);
            } else if (tryWarmupFallback(attempt)) {
                MAIN.postDelayed(TIMEOUT, 180000);
            } else {
                sessionActive = false;
                show("Game service binding rejected and no foreground launcher Activity was available to wake :game_host. Return to Client Base and tap Launch again.");
            }
        } catch (Exception e) {
            sessionActive = false;
            serviceBound = false;
            show("Game service binding failed: " + e);
        }
    }

    private static boolean tryWarmupFallback(final int attempt) {
        Activity launcher = foreground.get();
        if (launcher == null || launcher.isFinishing() || launcher.isDestroyed()) return false;
        show("Android blocked cold service bind for :game_host · waking process via private warmup Activity...");
        ResultReceiver warmupReceiver = new ResultReceiver(MAIN) {
            @Override protected void onReceiveResult(int resultCode, Bundle data) {
                if (!sessionActive || attempt != generation || resultCode != GameHostWarmupActivity.RESULT_READY || data == null) {
                    return;
                }
                if (data.getInt("id") != generation || data.getInt("uid", -1) != Process.myUid()) {
                    return;
                }
                int child = data.getInt("pid", 0);
                IBinder warmBinder = data.getBinder("binder");
                if (child <= 0 || child == Process.myPid() || warmBinder == null || !warmBinder.isBinderAlive()) {
                    stop("Warmup Activity returned invalid :game_host binder");
                    return;
                }
                if (pid == 0) {
                    pid = child;
                    saveRun("none");
                }
                // Try attaching standard Service binding now that :game_host is running
                // (ActiveServices fast-path when ProcessRecord is already live).
                if (!serviceBound) {
                    try {
                        serviceBound = app.bindService(
                                new Intent(app, GameHostService.class), connection, Context.BIND_AUTO_CREATE);
                    } catch (Exception ignored) {
                        serviceBound = false;
                    }
                }
                // Connect immediately over the verified live :game_host IBinder so preparation
                // proceeds even on ROMs where bindService remains blocked by mBadProcesses.
                connectHostBinder(attempt, warmBinder);
            }
        };
        try {
            launcher.startActivity(GameHostWarmupActivity.intent(launcher, attempt, warmupReceiver));
            return true;
        } catch (Exception error) {
            show("Warmup Activity launch failed: " + error);
            return false;
        }
    }

    private static void connectHostBinder(final int attempt, IBinder service) {
        if (!sessionActive || attempt != generation || service == null) return;
        binder = service;
        GameHostLiveBinder.attach(service, () -> {
            if (attempt == generation) disconnected();
        });
        if (prepareSent) return;
        prepareSent = true;
        try {
            Message request = Message.obtain(null, 1);
            request.replyTo = RECEIVER;
            Bundle data = new Bundle();
            data.putInt("id", generation);
            request.setData(data);
            new Messenger(service).send(request);
        } catch (Exception e) {
            stop("IPC preparation failed: " + e);
        }
    }

    private static final Runnable TIMEOUT = () -> stop("180-second startup timeout; host cleanup requested");

    private static final Messenger RECEIVER = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        Bundle data = message.getData();
        if (!sessionActive || data.getInt("id") != generation || data.getInt("uid", -1) != Process.myUid()) return true;
        int child = data.getInt("pid", 0);
        if (child <= 0 || child == Process.myPid() || (pid != 0 && child != pid)) return true;
        if (pid == 0) {
            pid = child;
            startedAt = data.getLong("started_at", startedAt);
            saveRun("none");
        }
        if (data.getBoolean("reattached") && data.getBoolean("activity_created")) {
            launched = true;
            MAIN.removeCallbacks(TIMEOUT);
            releaseAutoCreateBinding();
        }
        show(data.getString("text", "Empty game stage"));
        if (data.getBoolean("blocked")) {
            MAIN.removeCallbacks(TIMEOUT);
            stop("Preparation blocked; host cleanup requested");
        } else if (data.getBoolean("ready")) {
            ready = true;
            launchIfReady();
        }
        return true;
    }));

    private static ServiceConnection connectionFor(final int attempt) {
        return new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder service) {
                if (!sessionActive || attempt != generation) return;
                connectHostBinder(attempt, service);
            }
            @Override public void onServiceDisconnected(ComponentName n) {
                if (attempt == generation && (binder == null || !binder.isBinderAlive())) disconnected();
            }
            @Override public void onBindingDied(ComponentName n) {
                if (attempt == generation && (binder == null || !binder.isBinderAlive())) disconnected();
            }
            @Override public void onNullBinding(ComponentName n) {
                if (attempt == generation && binder == null) stop("Null game binding");
            }
        };
    }

    private static void launchIfReady() {
        Activity a = foreground.get();
        if (!sessionActive || !ready || launched || a == null || a.isFinishing() || a.isDestroyed()) return;
        ready = false;
        launched = true;
        try {
            a.startActivity(new Intent().setComponent(new ComponentName(a.getPackageName(), GameActivityRuntime.WRAPPER)));
            // Bound child is now owned by the real Activity, not one-shot service cleanup.
            // Keep startup timeout until original onResume returns, not merely a successful startActivity call.
            MAIN.postDelayed(STARTUP_WATCH, 500);
        } catch (Exception e) {
            stop("Framework launch rejected: " + e);
        }
    }

    private static final Runnable STARTUP_WATCH = new Runnable() {
        @Override public void run() {
            if (!sessionActive || !launched) return;
            String text = GameHostReport.read(GameHostReport.stageFile(app));
            if (text.contains("ORIGINAL_ON_RESUME_RETURNED")) {
                MAIN.removeCallbacks(TIMEOUT);
                // Drop BIND_AUTO_CREATE ServiceConnection once MinecraftHostedActivity owns the
                // process so Android ActiveServices never auto-respawns :game_host on exit/crash.
                releaseAutoCreateBinding();
                return;
            }
            MAIN.postDelayed(this, 500);
        }
    };

    private static void releaseAutoCreateBinding() {
        if (serviceBound) {
            serviceBound = false;
            try { app.unbindService(connection); }
            catch (Exception ignored) {}
        }
        try { app.stopService(new Intent(app, GameHostService.class)); }
        catch (Exception ignored) {}
    }

    private static void unbind() {
        MAIN.removeCallbacks(TIMEOUT);
        MAIN.removeCallbacks(STARTUP_WATCH);
        sessionActive = false;
        prepareSent = false;
        ready = false;
        binder = null;
        GameHostLiveBinder.clear();
        releaseAutoCreateBinding();
    }

    private static void disconnected() {
        if (!sessionActive) return;
        unbind();
        refresh();
        collect(); // Never kill/rebind or rerun initialization on unexpected death.
    }

    public static void stop() { stop("User requested game-process termination (SIGKILL, not proof of a crash)"); }

    private static void stop(String note) {
        if (!sessionActive) return;
        saveRun(note);
        boolean ownLiveChild = pid > 0 && pid != Process.myPid() && binder != null && binder.isBinderAlive();
        if (ownLiveChild) Process.killProcess(pid); // PID came only from current same-UID private-service IPC.
        unbind();
        refresh();
        collect();
    }

    public static void refresh() {
        if (app == null) return;
        File stage = GameHostReport.stageFile(app), exit = exitFile();
        if (stage.isFile()) {
            show(exit.isFile() && exit.lastModified() >= stage.lastModified()
                    ? GameHostReport.read(exit) + "\n\nLifecycle milestones:\n" + GameHostReport.read(stage)
                    : GameHostReport.read(stage));
        }
    }

    public static void collect() {
        if (app == null || collecting) return;
        collecting = true;
        final int expected = generation, request = ++collectionId;
        MAIN.postDelayed(() -> {
            if (collecting && request == collectionId) {
                collecting = false;
                ++collectionId;
                show(current() + "\nExit collection UI deadline reached; retry collection without relaunching game.");
            }
        }, 12000);
        new Thread(() -> {
            String details;
            try {
                JSONObject run = new JSONObject(GameHostReport.read(runFile()));
                ProbeExitDiagnostics.Run identity = new ProbeExitDiagnostics.Run(run.getInt("pid"), run.getInt("uid"),
                        run.getLong("started_at"), run.getString("cleanup"), ":game_host");
                details = ProbeExitDiagnostics.collect(app, identity);
                details = ProbeExitDiagnostics.summarizeGame(app, identity,
                        GameHostReport.read(GameHostReport.stageFile(app)), details) + "\n" + details;
            } catch (Exception e) {
                details = "GAME_EXIT_UNAVAILABLE\n" + e;
            }
            final String result = details;
            MAIN.post(() -> {
                if (expected != generation || request != collectionId) return;
                collecting = false;
                try { GameHostReport.write(exitFile(), result); }
                catch (Exception ignored) {}
                refresh();
            });
        }, "game-exit-details").start();
    }
}
