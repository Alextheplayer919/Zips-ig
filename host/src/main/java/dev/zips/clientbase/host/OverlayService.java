package dev.zips.clientbase.host;

import android.app.Application;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

/** Opt-in foreground overlay, normal UI process, non-sticky and independent of game lifetime. */
public final class OverlayService extends Service {
    static final String START="dev.zips.clientbase.overlay.START",STOP="dev.zips.clientbase.overlay.STOP";
    private static final String CHANNEL="zips_external_controls";
    static final int NOTIFICATION=1002;
    private static volatile OverlayService current;
    private OverlayWindow windows;
    private AppOpsManager appOps;
    private boolean destroyed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AppOpsManager.OnOpChangedListener permissionChanged=(operation,pkg)->main.post(()->{
        if(!destroyed&&!Settings.canDrawOverlays(this))fail("Overlay permission revoked; overlay stopped");
    });
    static boolean stopped(){return current==null;}
    static boolean running(){return current!=null&&current.windows!=null;}
    static OverlayWindow activeWindows(){return current==null?null:current.windows;}
    static void stop(Context context){context.stopService(new Intent(context,OverlayService.class));}
    @Override public void onCreate(){
        super.onCreate();current=this;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Zips external controls",NotificationManager.IMPORTANCE_LOW));
        appOps=getSystemService(AppOpsManager.class);
        try{appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,getPackageName(),permissionChanged);}
        catch(Exception error){Log.w("ZipsOverlay","Permission observer unavailable; window operations still check permission",error);}
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(!START.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        try{
            if(!getPackageName().equals(Application.getProcessName()))throw new SecurityException("Overlay must remain in the UI process");
            startForeground(NOTIFICATION,notification());
            if(!Settings.canDrawOverlays(this))throw new SecurityException("Draw-over-other-apps permission is not granted");
            if(windows==null){
                Context display=createDisplayContext(getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY));
                Context visual=new ContextThemeWrapper(display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null),android.R.style.Theme_Material_NoActionBar);
                windows=new OverlayWindow(visual,this::stopSelf,this::fail);windows.show();
            }
        }catch(Exception error){fail("Overlay unavailable: "+error.getMessage());}
        return START_NOT_STICKY;
    }
    private Notification notification(){
        int immutable=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        PendingIntent controls=PendingIntent.getActivity(this,1,ControlsActivity.intent(this),immutable);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,OverlayService.class).setAction(STOP),immutable);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Zips external controls").setContentText("Overlay enabled · tap for Controls, or Stop to remove")
                .setContentIntent(controls).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Stop overlay",stop).build()).build();
    }
    private void fail(String message){
        Log.w("ZipsOverlay",message);Toast.makeText(this,message,Toast.LENGTH_LONG).show();
        if(windows!=null){windows.close();windows=null;}stopSelf();
    }
    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);if(windows!=null)windows.reflow();}
    @Override public void onDestroy(){
        destroyed=true;
        if(appOps!=null){try{appOps.stopWatchingMode(permissionChanged);}catch(Exception ignored){}}
        main.removeCallbacksAndMessages(null);
        if(windows!=null){windows.close();windows=null;}
        stopForeground(STOP_FOREGROUND_REMOVE);if(current==this)current=null;super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
