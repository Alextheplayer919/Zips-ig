package dev.zips.clientbase.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

final class OverlayPermission {
    static void requestStart(Activity activity){
        if(!Settings.canDrawOverlays(activity)){
            new AlertDialog.Builder(activity).setTitle("Optional external overlay")
                .setMessage("Android must allow this app to display over other apps. The menu stays outside Minecraft’s renderer and uses a foreground service. It may also be visible over other apps until stopped. Return here and tap Enable again after granting permission. No game or memory operation starts automatically.")
                .setNegativeButton("Cancel",null).setPositiveButton("Open settings",(d,w)->{
                    try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+activity.getPackageName())));}
                    catch(Exception error){Toast.makeText(activity,"Overlay settings unavailable: "+error.getMessage(),Toast.LENGTH_LONG).show();}
                }).show();return;
        }
        if(Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            new AlertDialog.Builder(activity).setTitle("Show the overlay notification?")
                .setMessage("Allow notifications to keep the Stop action visible in the notification shade. If denied, you can stop from Controls, the overlay, or Android’s active-apps panel. Enabling this overlay does not launch Minecraft.")
                .setNegativeButton("Cancel",null)
                .setNeutralButton("Start without notification permission",(d,w)->start(activity))
                .setPositiveButton("Request permission",(d,w)->activity.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},411)).show();return;
        }
        start(activity);
    }
    private static void start(Activity activity){
        try{activity.startForegroundService(new Intent(activity,OverlayService.class).setAction(OverlayService.START));}
        catch(Exception error){Toast.makeText(activity,"Could not start overlay: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }
}
