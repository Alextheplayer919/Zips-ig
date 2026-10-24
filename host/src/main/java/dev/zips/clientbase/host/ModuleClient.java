package dev.zips.clientbase.host;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Explicit module command client to :game_host. Never auto-launches Minecraft. */
final class ModuleClient {
    private static final AtomicInteger IDS = new AtomicInteger();

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int operation;
    private final boolean fly, aura, attackMobs, multiTarget, checkHurtTime;
    private final boolean requireRunningHost;
    private final int requestId = IDS.updateAndGet(i -> i == Integer.MAX_VALUE ? 1 : i + 1);
    private Consumer<ModuleState> callback;
    private boolean bound, done;

    private final Runnable timeout = this::onTimeout;
    private final Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), this::onReply));

    ModuleClient(Context context, int operation,
                 boolean fly, boolean aura,
                 boolean attackMobs, boolean multiTarget, boolean checkHurtTime,
                 boolean requireRunningHost,
                 Consumer<ModuleState> callback) {
        if (!ModuleProtocol.operation(operation)) {
            throw new IllegalArgumentException("Invalid module operation: " + operation);
        }
        this.context = context.getApplicationContext();
        this.operation = operation;
        this.fly = fly;
        this.aura = aura;
        this.attackMobs = attackMobs;
        this.multiTarget = multiTarget;
        this.checkHurtTime = checkHurtTime;
        this.requireRunningHost = requireRunningHost;
        this.callback = callback;
    }

    static boolean isGameHostProcessRunning(Context context) {
        if (GameHostLiveBinder.get() != null) return true;
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return false;
        String expected = context.getPackageName() + ":game_host";
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) return false;
        for (ActivityManager.RunningAppProcessInfo info : procs) {
            if (expected.equals(info.processName)) return true;
        }
        return false;
    }

    private void onTimeout() {
        finish(ModuleState.unavailable(
                "Module IPC timed out · check Bedrock session", attackMobs, multiTarget, checkHurtTime));
    }

    private boolean onReply(Message message) {
        if (!done && ModuleProtocol.matches(message, operation, requestId)
                && message.getData().getInt("pid") != Process.myPid()) {
            finish(ModuleState.parse(message.getData().getString("payload", "")));
        }
        return true;
    }

    private boolean sendOverBinder(IBinder binder) {
        if (binder == null || !binder.isBinderAlive()) return false;
        try {
            Message msg = ModuleProtocol.request(
                    operation, requestId, fly, aura, attackMobs, multiTarget, checkHurtTime);
            msg.replyTo = receiver;
            new Messenger(binder).send(msg);
            return true;
        } catch (android.os.RemoteException error) {
            return false;
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (done) return;
            if (!sendOverBinder(binder)) {
                finish(ModuleState.unavailable("Module IPC unavailable · game process unreachable",
                        attackMobs, multiTarget, checkHurtTime));
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            finish(ModuleState.unavailable("Game process disconnected", attackMobs, multiTarget, checkHurtTime));
        }

        @Override public void onBindingDied(ComponentName name) {
            finish(ModuleState.unavailable("Module binding died", attackMobs, multiTarget, checkHurtTime));
        }

        @Override public void onNullBinding(ComponentName name) {
            finish(ModuleState.unavailable("Module binding unavailable", attackMobs, multiTarget, checkHurtTime));
        }
    };

    void start() {
        IBinder liveBinder = GameHostLiveBinder.get();
        if (liveBinder != null && sendOverBinder(liveBinder)) {
            main.postDelayed(timeout, 6000);
            return;
        }
        if (requireRunningHost && !isGameHostProcessRunning(context)) {
            main.post(() -> finish(ModuleState.unavailable(
                    "Launch Bedrock first · game process is not running", attackMobs, multiTarget, checkHurtTime)));
            return;
        }
        try {
            bound = context.bindService(
                    new Intent(context, GameHostService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                finish(ModuleState.unavailable(
                        "Launch Bedrock from Client Base first · cold :game_host service bind is blocked until launch wakes it",
                        attackMobs, multiTarget, checkHurtTime));
            } else {
                main.postDelayed(timeout, 6000);
            }
        } catch (Exception error) {
            finish(ModuleState.unavailable("Module connection error: " + error.getMessage(),
                    attackMobs, multiTarget, checkHurtTime));
        }
    }

    private void finish(ModuleState result) {
        if (done) return;
        Consumer<ModuleState> target = callback;
        cancel();
        if (target != null) target.accept(result);
    }

    void cancel() {
        done = true;
        callback = null;
        main.removeCallbacks(timeout);
        if (bound) {
            bound = false;
            try { context.unbindService(connection); }
            catch (IllegalArgumentException ignored) {}
        }
    }
}
