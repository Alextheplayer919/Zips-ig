package dev.zips.clientbase.host;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import java.lang.ref.WeakReference;

/**
 * Private, non-exported warmup Activity in :game_host.
 *
 * In Android 13 AOSP, AppErrors.mBadProcesses keys bad processes by
 * ("dev.zips.clientbase.host:game_host", uid), whereas Force Stop and same-UID
 * APK updates call AppErrors.clearBadProcess(ApplicationInfo) which only clears
 * ("dev.zips.clientbase.host", uid). Consequently, cold Context.bindService()
 * returns false even after Force Stop until device reboot.
 *
 * ActivityTaskSupervisor starts Activities without consulting mBadProcesses,
 * spawning a fresh :game_host ProcessRecord so GameHostService can bind and/or
 * hand off its live IBinder directly via ResultReceiver without rebooting.
 */
public final class GameHostWarmupActivity extends Activity {
    static final String EXTRA_ATTEMPT = "attempt";
    static final String EXTRA_RECEIVER = "receiver";
    static final int RESULT_READY = 1;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<GameHostWarmupActivity> active = new WeakReference<>(null);
    private final Runnable safetyFinish = this::closeQuietly;

    static Intent intent(Context launcher, int attempt, ResultReceiver receiver) {
        return new Intent()
                .setComponent(new ComponentName(launcher.getPackageName(), GameHostWarmupActivity.class.getName()))
                .putExtra(EXTRA_ATTEMPT, attempt)
                .putExtra(EXTRA_RECEIVER, receiver);
    }

    static void dismissIfOpen() {
        MAIN.post(() -> {
            GameHostWarmupActivity current = active.get();
            if (current != null) current.closeQuietly();
        });
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);
        overridePendingTransition(0, 0);
        if (!(getPackageName() + ":game_host").equals(Application.getProcessName())) {
            closeQuietly();
            return;
        }
        Intent launch = getIntent();
        ResultReceiver receiver = launch == null ? null : launch.getParcelableExtra(EXTRA_RECEIVER);
        int attempt = launch == null ? 0 : launch.getIntExtra(EXTRA_ATTEMPT, 0);
        if (launch != null) {
            launch.replaceExtras((Bundle) null);
        }
        if (receiver == null || attempt <= 0) {
            closeQuietly();
            return;
        }
        active = new WeakReference<>(this);
        try { startService(new Intent(this, GameHostService.class)); }
        catch (Exception ignored) {}
        MAIN.postDelayed(safetyFinish, 15000L);

        Bundle data = new Bundle();
        data.putInt("id", attempt);
        data.putInt("pid", Process.myPid());
        data.putInt("uid", Process.myUid());
        data.putBinder("binder", GameHostService.sharedBinder(getApplicationContext()));
        receiver.send(RESULT_READY, data);
    }

    private void closeQuietly() {
        MAIN.removeCallbacks(safetyFinish);
        if (active.get() == this) active.clear();
        if (!isFinishing() && !isDestroyed()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override protected void onDestroy() {
        MAIN.removeCallbacks(safetyFinish);
        if (active.get() == this) active.clear();
        super.onDestroy();
    }
}
