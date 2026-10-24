package dev.zips.clientbase.host;

import android.app.Activity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import dev.zips.clientbase.contract.ActivityEnvironment;

/** Passive production observer, independently testable without loading any Minecraft class. */
public final class HostedSurfaceObserver {
    private final Activity activity;
    private final ActivityEnvironment environment;
    public HostedSurfaceObserver(Activity activity, ActivityEnvironment environment) {
        this.activity = activity; this.environment = environment;
    }
    private void emit(String text) { environment.event(activity, text); }
    public SurfaceHolder.Callback callback() {
        return new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { emit("SURFACE_CREATED (not title screen)"); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { emit("SURFACE_CHANGED " + width + "x" + height); }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { emit("SURFACE_DESTROYED"); }
        };
    }
    public void observe(View root) { observe(root, new int[] {256}); }
    private void observe(View view, int[] budget) {
        if (--budget[0] < 0) return;
        if (view instanceof SurfaceView) ((SurfaceView) view).getHolder().addCallback(callback());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) observe(group.getChildAt(i), budget);
        }
    }
}
