package dev.zips.clientbase.host;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Consumer;

/** Bounded, non-focusable Android windows in the UI process. Nothing is attached to MC. */
final class OverlayWindow {
    private final Context context;
    private final WindowManager manager;
    private final Runnable stop;
    private final Consumer<String> failure;
    private Button chip;
    private LinearLayout panel;
    private WindowManager.LayoutParams chipParams,panelParams;
    private MemoryClient client;
    private String report="No memory snapshot yet. Launch Bedrock normally, then Refresh.";
    private boolean closed;
    OverlayWindow(Context context,Runnable stop,Consumer<String> failure){
        this.context=context;this.stop=stop;this.failure=failure;
        manager=context.getSystemService(WindowManager.class);
    }
    static WindowManager.LayoutParams params(int width,int height){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;
        p.setFitInsetsTypes(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        return p;
    }
    private int[] area(){
        WindowMetrics metrics=manager.getCurrentWindowMetrics();
        Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        return new int[]{Math.max(1,metrics.getBounds().width()-insets.left-insets.right),Math.max(1,metrics.getBounds().height()-insets.top-insets.bottom)};
    }
    void show(){
        if(closed||chip!=null)return;
        if(!Settings.canDrawOverlays(context))throw new SecurityException("Draw-over-other-apps permission is not granted");
        int[] area=area();chip=ClientUi.button(context,"ZIPS",true);chip.setId(R.id.overlay_chip);
        chip.setContentDescription("Open Zips overlay. Drag to move.");
        chipParams=params(OverlayGeometry.size(ClientUi.dp(context,76),area[0]),OverlayGeometry.size(ClientUi.dp(context,48),area[1]));
        chipParams.x=OverlayGeometry.clamp(ClientUi.dp(context,16),chipParams.width,area[0]);chipParams.y=OverlayGeometry.clamp(ClientUi.dp(context,64),chipParams.height,area[1]);
        chip.setOnClickListener(v->toggle());drag(chip,chip,chipParams);
        manager.addView(chip,chipParams);
    }
    private void toggle(){
        if(closed)return;
        if(!Settings.canDrawOverlays(context)){failure.accept("Overlay permission was revoked");return;}
        if(panel!=null){hidePanel();return;}
        try{showPanel();}catch(Exception error){failure.accept("Overlay menu unavailable: "+error.getMessage());}
    }
    private void showPanel(){
        int[] area=area();panel=ClientUi.card(context);panel.setId(R.id.overlay_panel);panel.setClickable(true);
        panelParams=params(OverlayGeometry.size(ClientUi.dp(context,360),area[0]),OverlayGeometry.size(ClientUi.dp(context,480),area[1]));
        panelParams.x=OverlayGeometry.clamp(chipParams.x,panelParams.width,area[0]);panelParams.y=OverlayGeometry.clamp(chipParams.y,panelParams.height,area[1]);
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=ClientUi.label(context,"ZIPS  /  MEMORY",16,ClientUi.ACCENT);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setContentDescription("Drag menu");heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));drag(title,panel,panelParams);
        Button close=ClientUi.button(context,"Close",false);close.setId(R.id.overlay_close);close.setOnClickListener(v->hidePanel());
        heading.addView(close,new LinearLayout.LayoutParams(ClientUi.dp(context,76),ClientUi.dp(context,44)));panel.addView(heading);
        ScrollView scroll=new ScrollView(context);LinearLayout body=ClientUi.column(context);scroll.addView(body);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(ClientUi.label(context,"External window · manual read-only IPC\nClose hides the panel. Android Back still belongs to the game.",12,ClientUi.MUTED));
        Button refresh=ClientUi.button(context,"Refresh memory snapshot",true);refresh.setId(R.id.overlay_refresh);body.addView(refresh);
        TextView output=ClientUi.label(context,report,12,ClientUi.TEXT);output.setId(R.id.overlay_report);output.setTypeface(Typeface.MONOSPACE);
        refresh.setOnClickListener(v->{
            if(client!=null||closed)return;refresh.setEnabled(false);output.setText("Requesting a read-only snapshot…");
            client=new MemoryClient(context,value->{client=null;report=value;if(!closed&&panel!=null){refresh.setEnabled(true);output.setText(value);}});client.start();
        });
        Button copy=ClientUi.button(context,"Copy memory report",false);body.addView(copy);
        copy.setOnClickListener(v->((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Engine memory",report)));
        Button halt=ClientUi.button(context,"Stop overlay",false);halt.setId(R.id.overlay_stop);halt.setOnClickListener(v->stop.run());body.addView(halt);
        body.addView(output);
        manager.addView(panel,panelParams);chip.setVisibility(View.GONE);
    }
    private void drag(View handle,View window,WindowManager.LayoutParams p){
        handle.setOnTouchListener(new View.OnTouchListener(){
            float x,y;int initialX,initialY;boolean moving;
            @Override public boolean onTouch(View view,MotionEvent event){
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN){x=event.getRawX();y=event.getRawY();initialX=p.x;initialY=p.y;moving=false;return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
                    float dx=event.getRawX()-x,dy=event.getRawY()-y;
                    if(Math.abs(dx)+Math.abs(dy)>ViewConfiguration.get(context).getScaledTouchSlop())moving=true;
                    if(moving){int[] bounds=area();p.x=OverlayGeometry.clamp(initialX+Math.round(dx),p.width,bounds[0]);p.y=OverlayGeometry.clamp(initialY+Math.round(dy),p.height,bounds[1]);update(window,p);}return true;
                }
                if(event.getActionMasked()==MotionEvent.ACTION_UP){if(!moving)view.performClick();return true;}
                return event.getActionMasked()==MotionEvent.ACTION_CANCEL;
            }
        });
    }
    private void update(View view,WindowManager.LayoutParams params){
        try{if(view!=null&&view.getParent()!=null)manager.updateViewLayout(view,params);}
        catch(Exception error){failure.accept("Overlay update failed: "+error.getMessage());}
    }
    void reflow(){
        if(closed)return;int[] bounds=area();
        if(chipParams!=null){chipParams.width=OverlayGeometry.size(ClientUi.dp(context,76),bounds[0]);chipParams.height=OverlayGeometry.size(ClientUi.dp(context,48),bounds[1]);chipParams.x=OverlayGeometry.clamp(chipParams.x,chipParams.width,bounds[0]);chipParams.y=OverlayGeometry.clamp(chipParams.y,chipParams.height,bounds[1]);update(chip,chipParams);}
        if(panelParams!=null){panelParams.width=OverlayGeometry.size(ClientUi.dp(context,360),bounds[0]);panelParams.height=OverlayGeometry.size(ClientUi.dp(context,480),bounds[1]);panelParams.x=OverlayGeometry.clamp(panelParams.x,panelParams.width,bounds[0]);panelParams.y=OverlayGeometry.clamp(panelParams.y,panelParams.height,bounds[1]);update(panel,panelParams);}
    }
    void hidePanel(){
        if(client!=null){client.cancel();client=null;}remove(panel);panel=null;panelParams=null;if(chip!=null)chip.setVisibility(View.VISIBLE);
    }
    private void remove(View view){if(view!=null&&view.getParent()!=null){try{manager.removeViewImmediate(view);}catch(Exception ignored){try{manager.removeView(view);}catch(Exception ignoredAgain){}}}}
    void close(){if(closed)return;closed=true;hidePanel();remove(chip);chip=null;chipParams=null;}
    // Package-private instrumentation access; not exported or callable via arbitrary intents.
    View chip(){return chip;} View panel(){return panel;}
}
