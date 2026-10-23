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
    private TextView report,mode;
    private Button refresh;
    private MemoryClient client;
    static Intent intent(Context c){return new Intent(c,ControlsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(ClientUi.BG);
        LinearLayout page=ClientUi.column(this);int p=ClientUi.dp(this,20);page.setPadding(p,p,p,p);scroll.addView(page);
        page.addView(ClientUi.label(this,"Z I P S   /   C O N T R O L S",13,ClientUi.ACCENT));
        page.addView(ClientUi.label(this,"Outside the game.\nConnected to the engine.",28,ClientUi.TEXT));
        page.addView(ClientUi.label(this,"0.6.0  ·  separate UI process  ·  read-only integration",13,ClientUi.MUTED));
        mode=ClientUi.label(this,"",12,ClientUi.ACCENT);page.addView(mode);updateMode();
        LinearLayout card=ClientUi.card(this);page.addView(card);
        card.addView(ClientUi.label(this,"ENGINE / MEMORY",12,ClientUi.ACCENT));
        card.addView(ClientUi.label(this,"A live snapshot, only on request.",22,ClientUi.TEXT));
        card.addView(ClientUi.label(this,"Inspect the already-loaded engine in :game_host. No periodic scans, memory writes, guessed offsets or gameplay modules.",14,ClientUi.MUTED));
        refresh=ClientUi.button(this,"Refresh memory snapshot",true);refresh.setId(R.id.memory_refresh);card.addView(refresh);
        refresh.setOnClickListener(v -> {
            if(client!=null)return;refresh.setEnabled(false);report.setText("Requesting a read-only snapshot…");
            client=new MemoryClient(this,text -> {client=null;refresh.setEnabled(true);report.setText(text);});client.start();
        });
        Button copy=ClientUi.button(this,"Copy memory report",false);card.addView(copy);
        copy.setOnClickListener(v -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Engine memory",report.getText())));
        report=ClientUi.label(this,state==null?"Launch Bedrock first, then refresh here. This request never launches the game.":state.getString("report","Refresh to inspect the current session."),12,ClientUi.TEXT);
        report.setId(R.id.memory_report);report.setTypeface(Typeface.MONOSPACE);report.setTextIsSelectable(true);card.addView(report);
        ClientUi.gap(page,16);LinearLayout guide=ClientUi.card(this);page.addView(guide);
        Button guideToggle=ClientUi.button(this,"Use alongside Minecraft  +",false);guideToggle.setId(R.id.controls_guide);guide.addView(guideToggle);
        TextView help=ClientUi.label(this,"1  Open this controls screen so its separate task appears in Recents.\n2  Launch Bedrock from the launcher.\n3  In Recents, use your device’s split-screen option to place Controls beside the game.\n\nAndroid 13 supports multi-resume, but OEM/device and game behavior vary. We cannot force Minecraft to keep ticking or promise split-screen compatibility. Outside split-screen, switching apps may pause it normally.\n\nThis screen does not render inside Minecraft or use overlay permission. Future implemented features can receive explicit commands over IPC; no fake toggles are included.",14,ClientUi.MUTED);
        help.setVisibility(View.GONE);guide.addView(help);guideToggle.setOnClickListener(v -> help.setVisibility(help.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        page.addView(ClientUi.label(this,"UI PID "+Process.myPid()+" / UID "+Process.myUid()+"\nEngine PID and identity are reported by the backend, not assumed from this screen.",12,ClientUi.MUTED));
        setContentView(scroll);
    }
    private void updateMode(){if(mode!=null)mode.setText(isInMultiWindowMode()?"MULTI-WINDOW ACTIVE · game behavior still depends on the device":"FULL SCREEN · split-screen is an optional device test");}
    @Override public void onMultiWindowModeChanged(boolean active,android.content.res.Configuration config){super.onMultiWindowModeChanged(active,config);updateMode();}
    @Override protected void onStop(){if(client!=null){client.cancel();client=null;refresh.setEnabled(true);}super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("report",report.getText().toString());}
}
