package dev.zips.clientbase.host;

import android.app.Application;

/** Normal host Application; no guest providers, package spoofing or hidden APIs. */
public final class ClientBaseApplication extends Application {
    private static ClientBaseApplication instance;
    private volatile android.content.Context gameResources;
    private volatile ClassLoader gameLoader;
    void installGameResources(android.content.Context resources, ClassLoader loader) {
        GameActivityRuntime.requireGameProcess(this);
        gameResources = resources; gameLoader = loader;
    }
    @Override public android.content.res.Resources getResources() {
        return gameResources == null ? super.getResources() : gameResources.getResources();
    }
    @Override public android.content.res.AssetManager getAssets() { return getResources().getAssets(); }
    @Override public ClassLoader getClassLoader() { return gameLoader == null ? super.getClassLoader() : gameLoader; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        String process = getProcessName();
        if (process != null && process.endsWith(":game_host")) {
            try { android.webkit.WebView.setDataDirectorySuffix("game_host"); }
            catch (IllegalStateException ignored) {}
        }
    }
    static ClientBaseApplication current() {
        if (instance == null) throw new IllegalStateException("Host Application is not ready");
        return instance;
    }
}
