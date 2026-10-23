package dev.zips.clientbase.host;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private persistent process owner. Unlike the one-shot probe, unbinding never kills a running Activity. */
public final class GameHostService extends Service {
    private final AtomicBoolean started = new AtomicBoolean();
    private Messenger client;
    private int id;
    private final long processStartedAt = System.currentTimeMillis();
    private boolean reattached;
    private final Messenger endpoint = new Messenger(new Handler(android.os.Looper.getMainLooper(), message -> {
        if (message.what != 1) return true;
        if (!started.compareAndSet(false, true)) {
            client = message.replyTo; id = message.getData().getInt("id"); reattached = true;
            report("REATTACHED_EXISTING_GAME_PROCESS (no restart or repeated initialization)",
                    GameActivityRuntime.isPrepared() && !GameActivityRuntime.hasCreatedActivity(), false);
            return true;
        }
        {
            client = message.replyTo; id = message.getData().getInt("id");
            report("GAME_PREPARATION_STARTED", false, false);
            new Thread(() -> {
                try {
                    GameActivityRuntime.prepare(this, text -> report(text, false, false));
                    report("READY_FOR_FRAMEWORK_LAUNCH", true, false);
                } catch (Exception | LinkageError e) {
                    report("GAME_PREPARATION_BLOCKED\n" + MinecraftBootstrap.describeFailure(e), false, true);
                }
            }, "bedrock-activity-prepare").start();
        }
        return true;
    }));
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    private synchronized void report(String text, boolean ready, boolean blocked) {
        String saved = GameHostReport.stage(this, text);
        Message response = Message.obtain(); Bundle data = new Bundle();
        data.putInt("id", id); data.putInt("pid", Process.myPid()); data.putInt("uid", Process.myUid());
        data.putLong("started_at", processStartedAt); data.putBoolean("reattached", reattached);
        data.putBoolean("activity_created", GameActivityRuntime.hasCreatedActivity());
        data.putString("text", saved); data.putBoolean("ready", ready); data.putBoolean("blocked", blocked);
        response.setData(data);
        try { if (client != null) client.send(response); } catch (android.os.RemoteException ignored) { }
    }
}
