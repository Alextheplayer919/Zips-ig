package dev.zips.clientbase.host;

import android.content.Context;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
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
    private void event(String label) { environment.event(this, label); }
    @Override public void onCreate(Bundle state) {
        event("ORIGINAL_ON_CREATE_ENTERED");
        super.onCreate(state); // Android framework delivers this; never called manually by the host.
        event("ORIGINAL_ON_CREATE_RETURNED (not title screen)");
        observeSurfaces(getWindow().getDecorView(), new int[] {256});
    }
    private void observeSurfaces(View view, int[] budget) {
        if (--budget[0] < 0) return;
        if (view instanceof SurfaceView) {
            ((SurfaceView) view).getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) { event("SURFACE_CREATED (not title screen)"); }
                @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) { event("SURFACE_CHANGED " + w + "x" + height); }
                @Override public void surfaceDestroyed(SurfaceHolder h) { event("SURFACE_DESTROYED"); }
            });
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) observeSurfaces(group.getChildAt(i), budget);
        }
    }
    @Override protected void onStart() { event("ORIGINAL_ON_START_ENTERED"); super.onStart(); event("ORIGINAL_ON_START_RETURNED"); }
    @Override protected void onResume() { event("ORIGINAL_ON_RESUME_ENTERED"); super.onResume(); event("ORIGINAL_ON_RESUME_RETURNED"); }
    @Override protected void onPause() { event("ORIGINAL_ON_PAUSE_ENTERED"); super.onPause(); }
    @Override protected void onStop() { event("ORIGINAL_ON_STOP_ENTERED"); super.onStop(); }
    @Override protected void onDestroy() { event("ORIGINAL_ON_DESTROY_ENTERED (game may exit its process)"); super.onDestroy(); }
}
