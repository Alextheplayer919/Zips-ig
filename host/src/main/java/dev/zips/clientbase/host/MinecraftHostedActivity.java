package dev.zips.clientbase.host;

import android.content.Context;
import android.os.Bundle;
import dev.zips.clientbase.contract.ActivityEnvironment;
import dev.zips.clientbase.contract.HostedActivity;

/** Child-defined host subclass of the ORIGINAL installed MainActivity; no game code patch. */
public final class MinecraftHostedActivity extends com.mojang.minecraftpe.MainActivity implements HostedActivity {
    private ActivityEnvironment environment;
    @Override public void configureHost(ActivityEnvironment value) {
        if (environment != null || getBaseContext() != null) throw new IllegalStateException("Already attached");
        environment = value;
    }
    @Override protected void attachBaseContext(Context base) {
        if (environment == null) throw new IllegalStateException("Game environment missing");
        super.attachBaseContext(environment.attach(base));
        event("ACTIVITY_ATTACHED");
    }
    @Override public void setTheme(int theme) { super.setTheme(environment == null ? theme : environment.mapTheme(theme)); }
    private ClassLoader enterGuestThread() {
        Thread thread = Thread.currentThread(); ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(getClassLoader()); return previous;
    }
    private void leaveGuestThread(ClassLoader previous) { Thread.currentThread().setContextClassLoader(previous); }
    private void event(String label) { environment.event(this, label); }
    @Override public void onCreate(Bundle state) {
        event("ORIGINAL_ON_CREATE_ENTERED");
        ClassLoader previous = enterGuestThread();
        try { super.onCreate(state); } // Android framework delivers this; never called manually by the host.
        finally { leaveGuestThread(previous); }
        event("ORIGINAL_ON_CREATE_RETURNED (not title screen)");
        new HostedSurfaceObserver(this, environment).observe(getWindow().getDecorView());
    }
    @Override protected void onStart() { event("ORIGINAL_ON_START_ENTERED"); ClassLoader previous = enterGuestThread(); try { super.onStart(); } finally { leaveGuestThread(previous); } event("ORIGINAL_ON_START_RETURNED"); }
    @Override protected void onResume() { event("ORIGINAL_ON_RESUME_ENTERED"); ClassLoader previous = enterGuestThread(); try { super.onResume(); } finally { leaveGuestThread(previous); } event("ORIGINAL_ON_RESUME_RETURNED"); }
    @Override protected void onPause() { event("ORIGINAL_ON_PAUSE_ENTERED"); ClassLoader previous = enterGuestThread(); try { super.onPause(); } finally { leaveGuestThread(previous); } }
    @Override protected void onStop() { event("ORIGINAL_ON_STOP_ENTERED"); ClassLoader previous = enterGuestThread(); try { super.onStop(); } finally { leaveGuestThread(previous); } }
    @Override protected void onDestroy() { event("ORIGINAL_ON_DESTROY_ENTERED (game may exit its process)"); ClassLoader previous = enterGuestThread(); try { super.onDestroy(); } finally { leaveGuestThread(previous); } }
}
