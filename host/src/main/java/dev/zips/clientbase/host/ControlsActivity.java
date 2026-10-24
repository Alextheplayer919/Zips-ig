package dev.zips.clientbase.host;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** External controls in the normal host UI process and an independent task, never in the MC window. */
public final class ControlsActivity extends Activity {
    private TextView report, moduleReport, mode;
    private Button refresh, moduleRefresh, moduleDisarm;
    private MemoryClient client;
    private ModuleClient moduleClient;

    static Intent intent(Context c) {
        return new Intent(c, ControlsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ClientUi.BG);
        LinearLayout page = ClientUi.column(this);
        int p = ClientUi.dp(this, 20);
        page.setPadding(p, p, p, p);
        scroll.addView(page);

        page.addView(ClientUi.label(this, "Z I P S   /   C O N T R O L S", 13, ClientUi.ACCENT));
        page.addView(ClientUi.label(this, "Outside the game.\nConnected to the engine.", 28, ClientUi.TEXT));
        page.addView(ClientUi.label(this, "0.6.5  ·  separate UI process  ·  internal 26.50 module bindings", 13, ClientUi.MUTED));

        LinearLayout overlay = ClientUi.card(this);
        page.addView(overlay);
        overlay.addView(ClientUi.label(this, "OPTIONAL / EXTERNAL OVERLAY", 12, ClientUi.ACCENT));
        overlay.addView(ClientUi.label(this, "A floating menu, not a game renderer.", 20, ClientUi.TEXT));
        overlay.addView(ClientUi.label(this,
                "Explicit Android permission required. Toggle MotionFly (Movement) and KillAura (Combat) from the overlay once Bedrock is running. Close hides the menu; Stop removes everything.",
                14, ClientUi.MUTED));
        Button enable = ClientUi.button(this, "Enable external overlay", true);
        enable.setId(R.id.overlay_enable);
        overlay.addView(enable);
        enable.setOnClickListener(v -> OverlayPermission.requestStart(this));
        Button disable = ClientUi.button(this, "Stop overlay", false);
        disable.setId(R.id.overlay_disable);
        overlay.addView(disable);
        disable.setOnClickListener(v -> OverlayService.stop(this));

        ClientUi.gap(page, 16);
        mode = ClientUi.label(this, "", 12, ClientUi.ACCENT);
        page.addView(mode);
        updateMode();

        LinearLayout moduleCard = ClientUi.card(this);
        page.addView(moduleCard);
        moduleCard.addView(ClientUi.label(this, "MODULE ENGINE / DIAGNOSTICS", 12, ClientUi.ACCENT));
        moduleCard.addView(ClientUi.label(this, "Internal packet Fly & native KillAura status.", 20, ClientUi.TEXT));
        moduleCard.addView(ClientUi.label(this,
                "Diagnostic counters stay here outside the in-game click GUI. Inspect hook installation, world session binding, packet motion injections and native attacks, or disarm all hooks.",
                14, ClientUi.MUTED));
        moduleRefresh = ClientUi.button(this, "Refresh module engine status", false);
        moduleRefresh.setId(R.id.module_engine_refresh);
        moduleCard.addView(moduleRefresh);
        moduleRefresh.setOnClickListener(v -> requestModuleOp(ModuleProtocol.QUERY));
        moduleDisarm = ClientUi.button(this, "Disarm native hooks", false);
        moduleDisarm.setId(R.id.module_engine_disarm);
        moduleCard.addView(moduleDisarm);
        moduleDisarm.setOnClickListener(v -> requestModuleOp(ModuleProtocol.DISARM));
        Button moduleCopy = ClientUi.button(this, "Copy module report", false);
        moduleCopy.setId(R.id.module_engine_copy);
        moduleCard.addView(moduleCopy);
        moduleCopy.setOnClickListener(v -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Module engine", moduleReport.getText())));
        moduleReport = ClientUi.label(this,
                state == null ? "No module query yet. Launch Bedrock, toggle modules in the overlay, or refresh here."
                        : state.getString("module_report", "Refresh to inspect live module status."),
                12, ClientUi.TEXT);
        moduleReport.setId(R.id.module_engine_report);
        moduleReport.setTypeface(Typeface.MONOSPACE);
        moduleReport.setTextIsSelectable(true);
        moduleCard.addView(moduleReport);

        ClientUi.gap(page, 16);
        LinearLayout card = ClientUi.card(this);
        page.addView(card);
        card.addView(ClientUi.label(this, "ENGINE / MEMORY", 12, ClientUi.ACCENT));
        card.addView(ClientUi.label(this, "A live snapshot, only on request.", 22, ClientUi.TEXT));
        card.addView(ClientUi.label(this,
                "Inspect the already-loaded engine in :game_host. Opcode 2 remains strictly read-only with no periodic scans.",
                14, ClientUi.MUTED));
        refresh = ClientUi.button(this, "Refresh memory snapshot", true);
        refresh.setId(R.id.memory_refresh);
        card.addView(refresh);
        refresh.setOnClickListener(v -> {
            if (client != null) return;
            refresh.setEnabled(false);
            report.setText("Requesting a read-only snapshot…");
            client = new MemoryClient(this, text -> {
                client = null;
                refresh.setEnabled(true);
                report.setText(text);
            });
            client.start();
        });
        Button copy = ClientUi.button(this, "Copy memory report", false);
        copy.setId(R.id.memory_copy);
        card.addView(copy);
        copy.setOnClickListener(v -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Engine memory", report.getText())));
        report = ClientUi.label(this,
                state == null ? "Launch Bedrock first, then refresh here. This request never launches the game."
                        : state.getString("report", "Refresh to inspect the current session."),
                12, ClientUi.TEXT);
        report.setId(R.id.memory_report);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextIsSelectable(true);
        card.addView(report);

        ClientUi.gap(page, 16);
        LinearLayout guide = ClientUi.card(this);
        page.addView(guide);
        Button guideToggle = ClientUi.button(this, "Use alongside Minecraft  +", false);
        guideToggle.setId(R.id.controls_guide);
        guide.addView(guideToggle);
        TextView help = ClientUi.label(this,
                "1  Enable the external overlay here, then launch Bedrock from the launcher.\n"
                        + "2  Enter a local world or server.\n"
                        + "3  Tap ZIPS to open Movement (MotionFly) or Combat (KillAura).\n\n"
                        + "Switches only turn ARMED/ON when the verified ARM64 1.26.50.4 engine accepts the live bindings.",
                14, ClientUi.MUTED);
        help.setVisibility(View.GONE);
        guide.addView(help);
        guideToggle.setOnClickListener(v -> help.setVisibility(help.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        page.addView(ClientUi.label(this,
                "UI PID " + Process.myPid() + " / UID " + Process.myUid()
                        + "\nEngine PID and identity are reported by the backend, not assumed from this screen.",
                12, ClientUi.MUTED));
        setContentView(scroll);
    }

    private void requestModuleOp(int op) {
        if (moduleClient != null) return;
        moduleRefresh.setEnabled(false);
        moduleDisarm.setEnabled(false);
        moduleReport.setText("Querying :game_host module engine…");
        moduleClient = new ModuleClient(this, op, false, false, true, false, false,
                /*requireRunningHost=*/false, state -> {
                    moduleClient = null;
                    moduleRefresh.setEnabled(true);
                    moduleDisarm.setEnabled(true);
                    moduleReport.setText(state.diagnosticReport);
                });
        moduleClient.start();
    }

    private void updateMode() {
        if (mode != null) {
            mode.setText(isInMultiWindowMode()
                    ? "MULTI-WINDOW ACTIVE · game behavior still depends on the device"
                    : "FULL SCREEN · split-screen is an optional device test");
        }
    }

    @Override public void onMultiWindowModeChanged(boolean active, android.content.res.Configuration config) {
        super.onMultiWindowModeChanged(active, config);
        updateMode();
    }

    @Override protected void onStop() {
        if (client != null) {
            client.cancel();
            client = null;
            refresh.setEnabled(true);
        }
        if (moduleClient != null) {
            moduleClient.cancel();
            moduleClient = null;
            moduleRefresh.setEnabled(true);
            moduleDisarm.setEnabled(true);
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("report", report.getText().toString());
        state.putString("module_report", moduleReport.getText().toString());
    }
}
