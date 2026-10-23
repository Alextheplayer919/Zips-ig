package dev.zips.clientbase.guest;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import dev.zips.clientbase.contract.ActivityEnvironment;
import dev.zips.clientbase.contract.HostedActivity;

/** Automated test fixture. Android, not the host, calls this Activity's lifecycle. */
public final class FrameworkGuestActivity extends Activity implements HostedActivity {
    private ActivityEnvironment environment;
    private final DemoGuestEntry nativeEntry = new DemoGuestEntry();

    @Override public void configureHost(ActivityEnvironment value) {
        if (environment != null || getBaseContext() != null) throw new IllegalStateException("Already configured/attached");
        environment = value;
    }
    @Override protected void attachBaseContext(Context base) {
        if (environment == null) throw new IllegalStateException("Host environment missing");
        super.attachBaseContext(environment.attach(base));
        environment.event(this, "attached");
    }
    @Override public void setTheme(int theme) {
        super.setTheme(environment == null ? theme : environment.mapTheme(theme));
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        environment.event(this, "onCreate");
        LinearLayout content = (LinearLayout) LayoutInflater.from(this).inflate(R.layout.framework_guest, null);
        SurfaceView surface = new SurfaceView(this);
        surface.setId(android.R.id.custom);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { environment.event(FrameworkGuestActivity.this, "surfaceCreated"); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { environment.event(FrameworkGuestActivity.this, "surfaceChanged"); }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { environment.event(FrameworkGuestActivity.this, "surfaceDestroyed"); }
        });
        content.addView(surface, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 240));
        setContentView(content);
    }
    @Override protected void onStart() { super.onStart(); environment.event(this, "onStart"); }
    @Override protected void onResume() { super.onResume(); environment.event(this, "onResume"); }
    @Override protected void onPause() { environment.event(this, "onPause"); super.onPause(); }
    @Override protected void onStop() { environment.event(this, "onStop"); super.onStop(); }
    @Override protected void onDestroy() { environment.event(this, "onDestroy"); super.onDestroy(); }
    public int nativePid() { return nativeEntry.processId(); }
    public int nativeUid() { return nativeEntry.userId(); }
}
