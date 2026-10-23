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
        page.setBackgroundColor(ClientUi.BG);
        page.addView(ClientUi.label(this,"Z I P S   /   B E D R O C K",13,ClientUi.ACCENT));
        page.addView(ClientUi.label(this,"A new way in.",36,ClientUi.TEXT));
        page.addView(ClientUi.label(this,"Client base 0.6.0  ·  Android 13  ·  Bedrock 26.50",13,ClientUi.MUTED));
        ClientUi.gap(page,16);
        LinearLayout launchCard=ClientUi.card(this); page.addView(launchCard);
        launchCard.addView(ClientUi.label(this,"READY TO LAUNCH",12,ClientUi.ACCENT));
        launchCard.addView(ClientUi.label(this,"Minecraft Bedrock",26,ClientUi.TEXT));
        launchCard.addView(ClientUi.label(this,"Your original installation, running in a host-owned process. The controls run separately from the game, with read-only memory inspection on demand.",15,ClientUi.MUTED));
        Button launchGame = ClientUi.button(this,"Launch Bedrock",true);
        launchGame.setId(R.id.launch_bedrock);
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
        launchCard.addView(launchGame);
        Button controls=ClientUi.button(this,"Open client controls  ↗",false);controls.setId(R.id.open_controls);
        controls.setOnClickListener(v -> startActivity(ControlsActivity.intent(this)));launchCard.addView(controls);
        ClientUi.gap(page,16);
        Button diagnostics=ClientUi.button(this,"Session diagnostics  +",false);page.addView(diagnostics);
        LinearLayout session=ClientUi.card(this);session.setVisibility(View.GONE);page.addView(session);
        diagnostics.setOnClickListener(v -> session.setVisibility(session.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        session.addView(ClientUi.label(this,"SESSION / DIAGNOSTICS",12,ClientUi.ACCENT));
        Button stopGame = ClientUi.button(this,"",false); stopGame.setText("Stop hosted game process");
        stopGame.setOnClickListener(v -> GameHostController.stop()); session.addView(stopGame);
        Button collectGame = ClientUi.button(this,"",false); collectGame.setText("Collect last game exit (no game execution)");
        collectGame.setOnClickListener(v -> GameHostController.collect()); session.addView(collectGame);
        Button shareGame = ClientUi.button(this,"",false); shareGame.setText("Share Activity preview (Save exports full log)");
        shareGame.setOnClickListener(v -> {
            GameHostController.refresh();
            Intent send = new Intent(Intent.ACTION_SEND); send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, GameHostController.current());
            startActivity(Intent.createChooser(send, "Share Activity report"));
        }); session.addView(shareGame);
        Button saveGame = ClientUi.button(this,"",false); saveGame.setText("Save Bedrock Activity report (.txt)");
        saveGame.setOnClickListener(v -> {
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            create.addCategory(Intent.CATEGORY_OPENABLE); create.setType("text/plain");
            create.putExtra(Intent.EXTRA_TITLE, "bedrock-activity-report.txt");
            startActivityForResult(create, SAVE_GAME_REPORT);
        }); session.addView(saveGame);
        gameStatus = text("No Activity attempt yet.", 14); gameStatus.setTextIsSelectable(true);
        gameStatus.setTypeface(Typeface.MONOSPACE); session.addView(gameStatus);
        ClientUi.gap(page,16);
        Button developer=ClientUi.button(this,"Developer tools  +",false); developer.setId(R.id.developer_tools);page.addView(developer);
        LinearLayout tools=ClientUi.card(this);tools.setVisibility(View.GONE);page.addView(tools);
        developer.setOnClickListener(v -> {
            boolean open=tools.getVisibility()!=View.VISIBLE;tools.setVisibility(open?View.VISIBLE:View.GONE);
            developer.setText(open?"Developer tools  −":"Developer tools  +");
        });
        tools.addView(text("Legacy diagnostics",22));
        tools.addView(text("Not needed for normal launch. Demo, import and bootstrap checks remain available for development.",14));
        Button load=ClientUi.button(this,"Load trusted demo",false);load.setId(R.id.load_demo);load.setOnClickListener(v -> loadGuest());tools.addView(load);
        status=text("Demo not loaded. Optional developer fixture only.",13);status.setId(R.id.load_status);tools.addView(status);
        guestPanel=new FrameLayout(this);guestPanel.setId(R.id.guest_panel);tools.addView(guestPanel);
        Button importEngine = ClientUi.button(this,"",false);
        importEngine.setText("Import supplied engine file");
        importEngine.setOnClickListener(v -> {
            if (EngineProbeController.isRunning() || EngineInput.IMPORTING.get()) return;
            Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            choose.setType("*/*");
            choose.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(choose, IMPORT_ENGINE);
        });
        tools.addView(importEngine);
        Button probe = ClientUi.button(this,"",false);
        probe.setText("Run engine link probe");
        probe.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Run native code in a private process?")
                .setMessage("Only use your trusted Minecraft installation. ELF constructors and matching native "
                        + "dependencies may execute and can crash. The private process is not a security sandbox. "
                        + "We do not call Minecraft's JNI_OnLoad or start the game. Keep the app open until it finishes.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run probe", (dialog, which) -> EngineProbeController.start()).show());
        tools.addView(probe);
        Button bootstrap = ClientUi.button(this,"",false);
        bootstrap.setText("Inspect Minecraft bootstrap (no code execution)");
        bootstrap.setOnClickListener(v -> EngineProbeController.inspectBootstrap());
        tools.addView(bootstrap);
        tools.addView(text("Reads the installed game's MainActivity DEX and static initializer as data. "
                + "No engine import or repeat link test is needed. Does not launch or initialize Minecraft.", 14));
        Button initialize = ClientUi.button(this,"",false);
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
        tools.addView(initialize);
        Button exitDetails = ClientUi.button(this,"",false);
        exitDetails.setText("Collect last probe exit details (no game execution)");
        exitDetails.setOnClickListener(v -> EngineProbeController.collectLastExitDetails());
        tools.addView(exitDetails);
        Button share = ClientUi.button(this,"",false);
        share.setText("Share current engine / bootstrap report");
        share.setOnClickListener(v -> {
            if (EngineProbeController.isRunning()) return;
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, engineStatus.getText().toString());
            startActivity(Intent.createChooser(send, "Share diagnostic report"));
        });
        tools.addView(share);
        engineStatus = text("Engine not imported.", 14);
        engineStatus.setTypeface(Typeface.MONOSPACE);
        engineStatus.setTextIsSelectable(true);
        tools.addView(engineStatus);
        setContentView(scroll);
        EngineProbeController.attach(this, this);
        GameHostController.attach(this, this);
    }

    @Override public void gameStatus(String text) {
        gameStatus.setText(text.length()>12000?text.substring(0,6000)+"\n… UI preview shortened. Save TXT for full report …\n"+text.substring(text.length()-6000):text);
    }
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
                    GameHostController.writeFullReport(out, snapshot);
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
        view.setTextColor(ClientUi.TEXT);
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
