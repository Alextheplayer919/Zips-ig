package dev.zips.clientbase.host;

import android.app.Application;

/** Normal host Application; no guest providers, package spoofing or hidden APIs. */
public final class ClientBaseApplication extends Application {
    private static ClientBaseApplication instance;
    @Override public void onCreate() { super.onCreate(); instance = this; }
    static ClientBaseApplication current() {
        if (instance == null) throw new IllegalStateException("Host Application is not ready");
        return instance;
    }
}
