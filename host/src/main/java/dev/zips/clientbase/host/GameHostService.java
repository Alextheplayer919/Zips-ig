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
    private final AtomicBoolean memoryBusy = new AtomicBoolean();
    private final AtomicBoolean moduleBusy = new AtomicBoolean();
    private Messenger client;
    private int id;
    private final long processStartedAt = System.currentTimeMillis();
    private boolean reattached;
    private final Messenger endpoint = new Messenger(new Handler(android.os.Looper.getMainLooper(), message -> {
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
            if (!moduleBusy.compareAndSet(false, true)) {
                sendModule(receiver, what, request,
                        ModuleState.unavailablePayload("Module engine busy · try again", mobs, multi, hurt));
                return true;
            }
            new Thread(() -> {
                String payload;
                try {
                    int nativeOp = what == ModuleProtocol.DISARM ? 2 : (what == ModuleProtocol.APPLY ? 1 : 0);
                    payload = GameActivityRuntime.moduleControl(this, nativeOp, fly, aura, mobs, multi, hurt);
                } catch (Exception | LinkageError error) {
                    payload = ModuleState.unavailablePayload(
                            "Unavailable · " + error.getMessage(), mobs, multi, hurt);
                } finally {
                    moduleBusy.set(false);
                }
                sendModule(receiver, what, request, payload);
            }, "game-module-control").start();
            return true; // Never prepares or launches Minecraft.
        }
        if (message.what == MemoryProtocol.SNAPSHOT) {
            if (!MemoryProtocol.accepts(message)) return true;
            final Messenger receiver=message.replyTo; final int request=message.arg1;
            if (!memoryBusy.compareAndSet(false,true)) {
                sendMemory(receiver,request,"Memory inspection already in progress");return true;
            }
            new Thread(() -> {
                String result;
                try { result=GameActivityRuntime.memorySnapshot(this); }
                catch (Exception | LinkageError error) { result="Memory unavailable: "+MinecraftBootstrap.describeFailure(error); }
                finally { memoryBusy.set(false); }
                sendMemory(receiver,request,result);
            },"game-memory-read").start();
            return true; // Does NOT prepare, initialize, launch or reattach the original Activity.
        }
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
    private static void sendMemory(Messenger receiver,int request,String report) {
        try { receiver.send(MemoryProtocol.response(request,report)); } catch(android.os.RemoteException ignored) { }
    }
    private static void sendModule(Messenger receiver, int what, int request, String payload) {
        try { receiver.send(ModuleProtocol.response(what, request, payload)); } catch(android.os.RemoteException ignored) { }
    }
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    @Override public void onDestroy() {
        super.onDestroy();
        // Abandoned preparation has no Activity owner; terminate only our own process.
        // Never apply the one-shot probe's unconditional kill to a running game Activity.
        if (!GameActivityRuntime.hasCreatedActivity()) Process.killProcess(Process.myPid());
    }
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
