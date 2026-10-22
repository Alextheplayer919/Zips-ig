package dev.zips.clientbase.host;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;
    private FrameLayout guestPanel;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding, padding, padding);
        scroll.addView(page);
        TextView eyebrow = text("CLIENT BASE / 0.1", 13);
        eyebrow.setTextColor(Color.rgb(113, 217, 193));
        page.addView(eyebrow);
        page.addView(text("One process.\nA separate guest.", 30));
        page.addView(text("Foundation test: load code, resources and JNI from the installed demo APK. "
                + "Minecraft hosting and engine hooks are not implemented.", 16));
        page.addView(text("Only install the demo built with this host. Loaded code runs with this "
                + "app's permissions; it is not sandboxed from the host.", 14));
        Button load = new Button(this);
        load.setId(R.id.load_demo);
        load.setText("Load trusted demo");
        load.setOnClickListener(v -> loadGuest());
        page.addView(load);
        status = text("Host PID: " + Process.myPid() + "\nHost UID: " + Process.myUid()
                + "\n\nInstall Client Base Demo, then tap above.", 14);
        status.setId(R.id.load_status);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        page.addView(status);
        guestPanel = new FrameLayout(this);
        guestPanel.setId(R.id.guest_panel);
        page.addView(guestPanel);
        setContentView(scroll);
    }

    private TextView text(String content, int size) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(230, 237, 243));
        view.setPadding(0, 12, 0, 12);
        return view;
    }

    private void loadGuest() {
        try {
            GuestRuntime.LoadedGuest guest = GuestRuntime.load(this);
            View view = guest.entry.createView(this, guest.resources);
            guestPanel.removeAllViews();
            guestPanel.addView(view);
            status.setText("PASS: guest JNI executes inside the host\n\nHost PID: " + Process.myPid()
                    + "\nNative PID: " + guest.entry.processId()
                    + "\nHost UID: " + Process.myUid()
                    + "\nNative UID: " + guest.entry.userId()
                    + "\nGuest installation UID: " + guest.installedUid
                    + "\nAPK paths loaded: " + guest.apkCount
                    + "\n\nThis proves the cooperative demo, not Minecraft hosting."
                    + "\nRestart the host after updating the demo APK.");
        } catch (PackageManager.NameNotFoundException error) {
            status.setText("Demo not installed. Install demo-guest-debug.apk from the same build, then retry.");
        } catch (Exception | LinkageError error) {
            status.setText("Load failed: " + error.getClass().getSimpleName() + "\n" + error.getMessage()
                    + "\n\nNo root, hidden-API bypass, or privileged fallback was attempted.");
        }
    }
}
