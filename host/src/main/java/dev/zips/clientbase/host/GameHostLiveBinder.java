package dev.zips.clientbase.host;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

/**
 * UI-process holder for the verified live :game_host Messenger IBinder.
 *
 * Allows GameHostController to drop its BIND_AUTO_CREATE ServiceConnection once
 * MinecraftHostedActivity reaches onResume (so Android ActiveServices never
 * auto-restarts :game_host on exit) and allows ModuleClient / MemoryClient to
 * talk to a live :game_host session even when Android mBadProcesses blocks
 * Context.bindService().
 */
final class GameHostLiveBinder {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static IBinder binder;
    private static IBinder.DeathRecipient recipient;

    private GameHostLiveBinder() {}

    static synchronized boolean attach(IBinder candidate, Runnable onDied) {
        clearLocked();
        if (candidate == null || !candidate.isBinderAlive()) return false;
        IBinder.DeathRecipient next = () -> MAIN.post(() -> {
            synchronized (GameHostLiveBinder.class) {
                if (binder == candidate) {
                    binder = null;
                    recipient = null;
                }
            }
            if (onDied != null) onDied.run();
        });
        try {
            candidate.linkToDeath(next, 0);
        } catch (RemoteException dead) {
            return false;
        }
        binder = candidate;
        recipient = next;
        return true;
    }

    static synchronized IBinder get() {
        if (binder != null && binder.isBinderAlive()) return binder;
        clearLocked();
        return null;
    }

    static synchronized void clear() {
        clearLocked();
    }

    private static void clearLocked() {
        if (binder != null && recipient != null) {
            try { binder.unlinkToDeath(recipient, 0); }
            catch (Exception ignored) {}
        }
        binder = null;
        recipient = null;
    }
}
