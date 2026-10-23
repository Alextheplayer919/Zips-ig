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

public final class MainActivity extends Activity implements EngineProbeController.Listener, GameHostController.Listener {
    private static final int IMPORT_ENGINE = 20;
    private static final int SAVE_GAME_REPORT = 21;
    private TextView gameStatus;
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
        TextView eyebrow = text("CLIENT BASE / 0.5.1", 13);
        eyebrow.setTextColor(Color.rgb(113, 217, 193));
        page.addView(eyebrow);
        page.addView(text("One process.\nA separate guest.", 30));
        page.addView(text("Foundation test: load code, resources and JNI from the installed demo APK. "
                + "Experimental Bedrock Activity hosting is below; engine hooks are not implemented.", 16));
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
        page.addView(text("Original Bedrock Activity attempt", 24));
        page.addView(text("Experimental: starts the original installed Bedrock Activity in a separate host-owned process. "
                + "This adapter does not install the original providers or reproduce every SDK/package environment. "
                + "An onCreate/Surface result is not a title-screen success.", 14));
        Button launchGame = new Button(this);
        launchGame.setText("Launch Bedrock Activity (experimental)");
        launchGame.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Run the original Bedrock Activity?")
                .setMessage("Use only your trusted original Minecraft 1.26.50.4 installation. We verify the supplied "
                        + "startup files and engine, then execute real constructors, lifecycle and native initialization. "
                        + "This can crash or hang. It runs with the host UID, network-state and INTERNET permissions, "
                        + "not Minecraft's UID or private data. Internet is enabled for original networked SDK startup. "
                        + "No provider/entitlement bypass or title-screen guarantee. Keep Client Base available for the report.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Launch", (dialog, which) -> GameHostController.start()).show());
        page.addView(launchGame);
        Button stopGame = new Button(this); stopGame.setText("Stop hosted game process");
        stopGame.setOnClickListener(v -> GameHostController.stop()); page.addView(stopGame);
        Button collectGame = new Button(this); collectGame.setText("Collect last game exit (no game execution)");
        collectGame.setOnClickListener(v -> GameHostController.collect()); page.addView(collectGame);
        Button shareGame = new Button(this); shareGame.setText("Share Bedrock Activity report");
        shareGame.setOnClickListener(v -> {
            GameHostController.refresh();
            Intent send = new Intent(Intent.ACTION_SEND); send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, GameHostController.current());
            startActivity(Intent.createChooser(send, "Share Activity report"));
        }); page.addView(shareGame);
        Button saveGame = new Button(this); saveGame.setText("Save Bedrock Activity report (.txt)");
        saveGame.setOnClickListener(v -> {
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            create.addCategory(Intent.CATEGORY_OPENABLE); create.setType("text/plain");
            create.putExtra(Intent.EXTRA_TITLE, "bedrock-activity-report.txt");
            startActivityForResult(create, SAVE_GAME_REPORT);
        }); page.addView(saveGame);
        gameStatus = text("No Activity attempt yet.", 14); gameStatus.setTextIsSelectable(true);
        gameStatus.setTypeface(Typeface.MONOSPACE); page.addView(gameStatus);
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
        Button bootstrap = new Button(this);
        bootstrap.setText("Inspect Minecraft bootstrap (no code execution)");
        bootstrap.setOnClickListener(v -> EngineProbeController.inspectBootstrap());
        page.addView(bootstrap);
        page.addView(text("Reads the installed game's MainActivity DEX and static initializer as data. "
                + "No engine import or repeat link test is needed. Does not launch or initialize Minecraft.", 14));
        Button initialize = new Button(this);
        initialize.setText("Initialize Minecraft Java / JNI (no Activity)");
        initialize.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Execute original Minecraft initialization?")
                .setMessage("Only use your trusted original Minecraft 1.26.50.4 installation. We verify the "
                        + "known initializer and exact supplied engine fingerprint, then let the real game class "
                        + "load its native libraries through the JVM. Java static initializers, SDK callbacks, "
                        + "ELF constructors and JNI_OnLoad may execute or crash in the private process. It shares "
                        + "this host's UID/permissions, including network-state access, and is not a security sandbox. No Activity, lifecycle or "
                        + "title screen is started. No engine re-import is needed. Keep the app open.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Initialize", (dialog, which) -> EngineProbeController.initializeMinecraft()).show());
        page.addView(initialize);
        Button exitDetails = new Button(this);
        exitDetails.setText("Collect last probe exit details (no game execution)");
        exitDetails.setOnClickListener(v -> EngineProbeController.collectLastExitDetails());
        page.addView(exitDetails);
        Button share = new Button(this);
        share.setText("Share current engine / bootstrap report");
        share.setOnClickListener(v -> {
            if (EngineProbeController.isRunning()) return;
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, engineStatus.getText().toString());
            startActivity(Intent.createChooser(send, "Share diagnostic report"));
        });
        page.addView(share);
        engineStatus = text("Engine not imported.", 14);
        engineStatus.setTypeface(Typeface.MONOSPACE);
        engineStatus.setTextIsSelectable(true);
        page.addView(engineStatus);
        setContentView(scroll);
        EngineProbeController.attach(this, this);
        GameHostController.attach(this, this);
    }

    @Override public void gameStatus(String text) { gameStatus.setText(text); }
    @Override protected void onResume() { super.onResume(); GameHostController.resumed(this); }
    @Override protected void onPause() { GameHostController.paused(this); super.onPause(); }
    @Override public void engineStatus(String text) { engineStatus.setText(text); }

    @Override protected void onDestroy() {
        EngineProbeController.detach(this);
        GameHostController.detach(this);
        super.onDestroy();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE_GAME_REPORT) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            GameHostController.refresh();
            String snapshot = GameHostController.current();
            Context application = getApplicationContext(); android.net.Uri target = data.getData();
            new Thread(() -> {
                String message;
                try (java.io.OutputStream out = application.getContentResolver().openOutputStream(target, "wt")) {
                    if (out == null) throw new java.io.IOException("Document is not writable");
                    out.write(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    message = "Activity report saved; review before sharing.";
                } catch (Exception error) { message = "Report save failed: " + error.getMessage(); }
                final String notice = message;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(application, notice, android.widget.Toast.LENGTH_LONG).show());
            }, "save-game-report").start();
            return;
        }
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
