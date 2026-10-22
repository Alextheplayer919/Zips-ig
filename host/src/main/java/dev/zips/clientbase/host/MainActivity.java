package dev.zips.clientbase.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import java.io.InputStream;
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

public final class MainActivity extends Activity implements EngineProbeController.Listener {
    private static final int IMPORT_ENGINE = 20;
    private TextView engineStatus;
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
        TextView eyebrow = text("CLIENT BASE / 0.2", 13);
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
        page.addView(text("Engine linkage experiment", 24));
        page.addView(text("Optional: import your exact engine archive or .so. This is a native link check, "
                + "not Minecraft startup. Matching dependencies must come from your trusted installed game.", 14));
        Button importEngine = new Button(this);
        importEngine.setText("Import supplied engine file");
        importEngine.setOnClickListener(v -> {
            if (EngineProbeController.isRunning() || EngineInput.IMPORTING.get()) return;
            Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            choose.setType("*/*");
            choose.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(choose, IMPORT_ENGINE);
        });
        page.addView(importEngine);
        Button probe = new Button(this);
        probe.setText("Run engine link probe");
        probe.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Run native code in a private process?")
                .setMessage("Only use your trusted Minecraft installation. ELF constructors and matching native "
                        + "dependencies may execute and can crash. The private process is not a security sandbox. "
                        + "We do not call Minecraft's JNI_OnLoad or start the game. Keep the app open until it finishes.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run probe", (dialog, which) -> EngineProbeController.start()).show());
        page.addView(probe);
        engineStatus = text("Engine not imported.", 14);
        engineStatus.setTypeface(Typeface.MONOSPACE);
        engineStatus.setTextIsSelectable(true);
        page.addView(engineStatus);
        setContentView(scroll);
        EngineProbeController.attach(this, this);
    }

    @Override public void engineStatus(String text) { engineStatus.setText(text); }

    @Override protected void onDestroy() {
        EngineProbeController.detach(this);
        super.onDestroy();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT_ENGINE || result != RESULT_OK || data == null || data.getData() == null) return;
        if (EngineProbeController.isRunning() || !EngineInput.IMPORTING.compareAndSet(false, true)) return;
        Context application = getApplicationContext();
        android.net.Uri uri = data.getData();
        EngineProbeController.show("Importing and hashing on a worker thread. Archive extraction needs "
                + "roughly 400 MB free space (more if replacing an existing engine). Please wait...");
        new Thread(() -> {
            try (InputStream input = application.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("Selected document cannot be read.");
                EngineInput.importStream(application, input);
                EngineProbeController.show("Engine imported: exact SHA-256 verified; no engine code executed. "
                        + "Now run the link probe. A matching installed Minecraft engine is required.");
            } catch (Exception | LinkageError | OutOfMemoryError error) {
                EngineProbeController.show("Import failed: " + error.getClass().getSimpleName() + "\n" + error.getMessage());
            } finally { EngineInput.IMPORTING.set(false); }
        }, "engine-import").start();
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
