package dev.zips.clientbase.host;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private persistent process owner. Unlike the one-shot probe, unbinding never kills a running Activity. */
public final class GameHostService extends Service {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean ACTIVE_LAUNCH_WINDOW = new AtomicBoolean();
    private static final AtomicBoolean MEMORY_BUSY = new AtomicBoolean();
    private static final AtomicBoolean MODULE_BUSY = new AtomicBoolean();
    private static final long PROCESS_STARTED_AT = System.currentTimeMillis();
    private static volatile Context hostContext;
    private static Messenger client;
    private static int id;
    private static boolean reattached;
    private static Messenger sharedEndpoint;

    static synchronized IBinder sharedBinder(Context context) {
        GameActivityRuntime.requireGameProcess(context);
        hostContext = context.getApplicationContext();
        if (sharedEndpoint == null) {
            sharedEndpoint = new Messenger(new Handler(Looper.getMainLooper(), GameHostService::handleMessage));
        }
        return sharedEndpoint.getBinder();
    }

    private static boolean handleMessage(Message message) {
        final Context appContext = hostContext != null ? hostContext : ClientBaseApplication.current();
        if (ModuleProtocol.operation(message.what)) {
            if (!ModuleProtocol.accepts(message)) return true;
            final Messenger receiver = message.replyTo;
            final int request = message.arg1;
            final int what = message.what;
            final Bundle input = message.getData();
            final boolean fly = input.getBoolean("fly", false);
            final boolean aura = input.getBoolean("aura", false);
            final boolean mobs = input.getBoolean("mobs", true);
            final boolean multi = input.getBoolean("multi", false);
            final boolean hurt = input.getBoolean("hurt", false);
            if (!MODULE_BUSY.compareAndSet(false, true)) {
                sendModule(receiver, what, request,
                        ModuleState.unavailablePayload("Module engine busy · try again", mobs, multi, hurt));
                return true;
            }
            new Thread(() -> {
                String payload;
                try {
                    int nativeOp = what == ModuleProtocol.DISARM ? 2 : (what == ModuleProtocol.APPLY ? 1 : 0);
                    payload = GameActivityRuntime.moduleControl(appContext, nativeOp, fly, aura, mobs, multi, hurt);
                } catch (Exception | LinkageError error) {
                    payload = ModuleState.unavailablePayload(
                            "Unavailable · " + error.getMessage(), mobs, multi, hurt);
                } finally {
                    MODULE_BUSY.set(false);
                }
                sendModule(receiver, what, request, payload);
            }, "game-module-control").start();
            return true; // Never prepares or launches Minecraft.
        }
        if (message.what == MemoryProtocol.SNAPSHOT) {
            if (!MemoryProtocol.accepts(message)) return true;
            final Messenger receiver = message.replyTo;
            final int request = message.arg1;
            if (!MEMORY_BUSY.compareAndSet(false, true)) {
                sendMemory(receiver, request, "Memory inspection already in progress");
                return true;
            }
            new Thread(() -> {
                String result;
                try { result = GameActivityRuntime.memorySnapshot(appContext); }
                catch (Exception | LinkageError error) { result = "Memory unavailable: " + MinecraftBootstrap.describeFailure(error); }
                finally { MEMORY_BUSY.set(false); }
                sendMemory(receiver, request, result);
            }, "game-memory-read").start();
            return true; // Does NOT prepare, initialize, launch or reattach the original Activity.
        }
        if (message.what != 1) return true;
        if (!STARTED.compareAndSet(false, true)) {
            synchronized (GameHostService.class) {
                client = message.replyTo;
                id = message.getData().getInt("id");
                reattached = true;
            }
            GameHostWarmupActivity.dismissIfOpen();
            report(appContext, "REATTACHED_EXISTING_GAME_PROCESS (no restart or repeated initialization)",
                    GameActivityRuntime.isPrepared() && !GameActivityRuntime.hasCreatedActivity(), false);
            return true;
        }
        synchronized (GameHostService.class) {
            client = message.replyTo;
            id = message.getData().getInt("id");
        }
        ACTIVE_LAUNCH_WINDOW.set(true);
        report(appContext, "GAME_PREPARATION_STARTED", false, false);
        new Thread(() -> {
            try {
                GameActivityRuntime.prepare(appContext, text -> report(appContext, text, false, false));
                GameHostWarmupActivity.dismissIfOpen();
                report(appContext, "READY_FOR_FRAMEWORK_LAUNCH", true, false);
            } catch (Exception | LinkageError e) {
                ACTIVE_LAUNCH_WINDOW.set(false);
                GameHostWarmupActivity.dismissIfOpen();
                report(appContext, "GAME_PREPARATION_BLOCKED\n" + MinecraftBootstrap.describeFailure(e), false, true);
            }
        }, "bedrock-activity-prepare").start();
        return true;
    }

    private static void sendMemory(Messenger receiver, int request, String report) {
        try { receiver.send(MemoryProtocol.response(request, report)); }
        catch (android.os.RemoteException ignored) {}
    }

    private static void sendModule(Messenger receiver, int what, int request, String payload) {
        try { receiver.send(ModuleProtocol.response(what, request, payload)); }
        catch (android.os.RemoteException ignored) {}
    }

    @Override public IBinder onBind(Intent intent) {
        return sharedBinder(getApplicationContext());
    }

    @Override public void onDestroy() {
        super.onDestroy();
        // Abandoned preparation or one-shot query with no prepared/launching Activity owner:
        // terminate only our own process. Never kill a running or launching game Activity.
        if (!GameActivityRuntime.hasCreatedActivity() && !ACTIVE_LAUNCH_WINDOW.get()) {
            Process.killProcess(Process.myPid());
        }
    }

    private static synchronized void report(Context context, String text, boolean ready, boolean blocked) {
        String saved = GameHostReport.stage(context, text);
        Message response = Message.obtain();
        Bundle data = new Bundle();
        data.putInt("id", id);
        data.putInt("pid", Process.myPid());
        data.putInt("uid", Process.myUid());
        data.putLong("started_at", PROCESS_STARTED_AT);
        data.putBoolean("reattached", reattached);
        data.putBoolean("activity_created", GameActivityRuntime.hasCreatedActivity());
        data.putString("text", saved);
        data.putBoolean("ready", ready);
        data.putBoolean("blocked", blocked);
        response.setData(data);
        try { if (client != null) client.send(response); }
        catch (android.os.RemoteException ignored) {}
    }
}
