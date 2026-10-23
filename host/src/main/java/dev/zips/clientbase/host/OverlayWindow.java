package dev.zips.clientbase.host;

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
    private ModuleCategory selected = ModuleCategory.MOVEMENT;
    private LinearLayout categoryBody;
    private Button[] categoryButtons;
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
        panelParams=params(OverlayGeometry.size(ClientUi.dp(context,480),area[0]),OverlayGeometry.size(ClientUi.dp(context,360),area[1]));
        panelParams.x=OverlayGeometry.clamp(chipParams.x,panelParams.width,area[0]);panelParams.y=OverlayGeometry.clamp(chipParams.y,panelParams.height,area[1]);
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=ClientUi.label(context,"ZIPS  /  CLIENT",16,ClientUi.ACCENT);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setContentDescription("Drag menu");heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));drag(title,panel,panelParams);
        Button close=ClientUi.button(context,"Close",false);close.setId(R.id.overlay_close);close.setOnClickListener(v->hidePanel());
        heading.addView(close,new LinearLayout.LayoutParams(ClientUi.dp(context,76),ClientUi.dp(context,44)));panel.addView(heading);
        LinearLayout sections = new LinearLayout(context);
        panel.addView(sections, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView navigation = new ScrollView(context);
        navigation.setFillViewport(true);
        LinearLayout categories = ClientUi.column(context);
        categories.addView(ClientUi.label(context, "CATEGORIES", 10, ClientUi.MUTED));
        navigation.addView(categories);
        sections.addView(navigation, new LinearLayout.LayoutParams(ClientUi.dp(context,112), -1));
        categoryButtons = new Button[ModuleCategory.values().length];
        for (ModuleCategory category : ModuleCategory.values()) {
            Button button = ClientUi.button(context, category.title, false);
            button.setId(category.viewId);
            button.setContentDescription(category.title + " category");
            button.setOnClickListener(v -> select(category));
            categoryButtons[category.ordinal()] = button;
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
            buttonParams.setMargins(0,ClientUi.dp(context,2),0,ClientUi.dp(context,2));
            categories.addView(button,buttonParams);
        }
        ScrollView content = new ScrollView(context);
        content.setFillViewport(true);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(0, -1, 1);
        contentParams.leftMargin = ClientUi.dp(context,16);
        sections.addView(content, contentParams);
        categoryBody = ClientUi.column(context);
        content.addView(categoryBody);
        select(selected);
        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(ClientUi.label(context, ModuleRegistry.all().size()+" REGISTERED", 11, ClientUi.MUTED), new LinearLayout.LayoutParams(0,-2,1));
        Button halt=ClientUi.button(context,"Stop overlay",false);
        halt.setId(R.id.overlay_stop);halt.setOnClickListener(v->stop.run());
        footer.addView(halt, new LinearLayout.LayoutParams(ClientUi.dp(context,120),ClientUi.dp(context,44)));
        panel.addView(footer);
        manager.addView(panel,panelParams);chip.setVisibility(View.GONE);
    }
    private void select(ModuleCategory category) {
        selected = category;
        for (ModuleCategory item : ModuleCategory.values()) {
            Button button = categoryButtons[item.ordinal()];
            boolean active = item == category;
            button.setSelected(active);
            button.setTextColor(active ? ClientUi.BG : ClientUi.TEXT);
            button.setBackground(ClientUi.shape(active ? ClientUi.ACCENT : ClientUi.CARD,
                    active ? 0 : ClientUi.LINE, context, 12));
        }
        categoryBody.removeAllViews();
        TextView title = ClientUi.label(context, category.title, 22, ClientUi.TEXT);
        title.setId(R.id.overlay_category_title);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        categoryBody.addView(title);
        java.util.List<ModuleRegistry.Entry> entries=ModuleRegistry.forCategory(category);
        if(entries.isEmpty()) {
            categoryBody.addView(ClientUi.label(context, "No modules yet", 15, ClientUi.MUTED));
            return;
        }
        for(ModuleRegistry.Entry entry:entries) {
            LinearLayout row=ClientUi.card(context);
            TextView name=ClientUi.label(context,entry.name,17,ClientUi.TEXT);
            name.setId(entry.viewId);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            row.addView(name);
            row.addView(ClientUi.label(context,entry.description,12,ClientUi.MUTED));
            row.addView(ClientUi.label(context,entry.blocker,12,ClientUi.MUTED));
            // No clickable toggle until a real live backend acknowledges enablement.
            categoryBody.addView(row);
            ClientUi.gap(categoryBody,8);
        }
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
        if(panelParams!=null){panelParams.width=OverlayGeometry.size(ClientUi.dp(context,480),bounds[0]);panelParams.height=OverlayGeometry.size(ClientUi.dp(context,360),bounds[1]);panelParams.x=OverlayGeometry.clamp(panelParams.x,panelParams.width,bounds[0]);panelParams.y=OverlayGeometry.clamp(panelParams.y,panelParams.height,bounds[1]);update(panel,panelParams);}
    }
    void hidePanel(){
        remove(panel);panel=null;panelParams=null;categoryBody=null;categoryButtons=null;
        if(chip!=null)chip.setVisibility(View.VISIBLE);
    }
    private void remove(View view){if(view!=null&&view.getParent()!=null){try{manager.removeViewImmediate(view);}catch(Exception ignored){try{manager.removeView(view);}catch(Exception ignoredAgain){}}}}
    void close(){if(closed)return;closed=true;hidePanel();remove(chip);chip=null;chipParams=null;}
    // Package-private instrumentation access; not exported or callable via arbitrary intents.
    View chip(){return chip;} View panel(){return panel;}
}
