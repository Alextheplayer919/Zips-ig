package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.Callable;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual Android overlay windows/foreground service; shell grants exist only in emulator tests. */
@RunWith(AndroidJUnit4.class)
public final class OverlayTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private void shell(String command)throws Exception{
        ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try(java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){byte[] buffer=new byte[2048];while(input.read(buffer)!=-1){}}
    }
    private void permission(boolean allowed)throws Exception{
        shell("appops set "+context().getPackageName()+" SYSTEM_ALERT_WINDOW "+(allowed?"allow":"ignore"));
        await(()->Settings.canDrawOverlays(context())==allowed);
    }
    private void await(Callable<Boolean> condition)throws Exception{
        long deadline=SystemClock.uptimeMillis()+8000;
        while(SystemClock.uptimeMillis()<deadline){if(MinecraftBootstrap.onMainThread(condition))return;SystemClock.sleep(50);}
        fail("Overlay condition not met within eight seconds");
    }
    private void cleanup()throws Exception{
        MinecraftBootstrap.onMainThread(()->{OverlayService.stop(context());return null;});
        await(OverlayService::stopped);
        permission(false);
    }
    @Test public void boundedGeometryAndParamsNeverTakeWindowFocus(){
        assertEquals(0,OverlayGeometry.clamp(-400,80,100));assertEquals(20,OverlayGeometry.clamp(900,80,100));
        assertEquals(0,OverlayGeometry.clamp(50,300,100));assertEquals(1,OverlayGeometry.size(300,0));
        WindowManager.LayoutParams params=OverlayWindow.params(320,240);
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,params.type);
        assertTrue((params.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0);
        assertTrue((params.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)!=0);
        assertFalse((params.flags&WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)!=0);
        assertTrue(params.width>0&&params.height>0);
    }
    @Test public void deniedPermissionFailsClosedWithoutWindow()throws Exception{
        permission(false);
        try(ActivityScenario<ControlsActivity> scenario=ActivityScenario.launch(ControlsActivity.intent(context()))){
            scenario.onActivity(activity->{
                OverlayWindow window=new OverlayWindow(activity,()->{},error->{});
                try{window.show();fail("Window created without permission");}catch(SecurityException expected){assertNull(window.chip());}
                finally{window.close();window.close();}
                assertNotNull(activity.findViewById(R.id.overlay_enable));assertNotNull(activity.findViewById(R.id.overlay_disable));
            });
        }finally{cleanup();}
    }
    @Test public void realForegroundCategoryMenuAndNotificationStop()throws Exception{
        permission(true);shell("pm grant "+context().getPackageName()+" android.permission.POST_NOTIFICATIONS");
        String before=GameHostReport.read(GameHostReport.stageFile(context()));
        try(ActivityScenario<ControlsActivity> scenario=ActivityScenario.launch(ControlsActivity.intent(context()))){
            scenario.onActivity(a->a.startForegroundService(new Intent(a,OverlayService.class).setAction(OverlayService.START)));
            await(OverlayService::running);
            OverlayWindow first=MinecraftBootstrap.onMainThread(OverlayService::activeWindows);
            scenario.onActivity(a->a.startForegroundService(new Intent(a,OverlayService.class).setAction(OverlayService.START)));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            MinecraftBootstrap.onMainThread(()->{
                assertSame(first,OverlayService.activeWindows());assertTrue(first.chip().isAttachedToWindow());
                first.chip().performClick();return null;
            });
            // addView schedules attachment/traversal; observe the real attached window on a later frame.
            await(()->first.panel()!=null&&first.panel().isAttachedToWindow());
            MinecraftBootstrap.onMainThread(()->{
                assertTrue(first.panel().isAttachedToWindow());
                WindowManager.LayoutParams p=(WindowManager.LayoutParams)first.panel().getLayoutParams();assertTrue(p.width>0&&p.height>0);
                assertNull(first.panel().findViewById(R.id.overlay_refresh));
                assertNull(first.panel().findViewById(R.id.overlay_report));
                for(ModuleCategory category:ModuleCategory.values()) {
                    first.panel().findViewById(category.viewId).performClick();
                    assertEquals(category.title,((TextView)first.panel().findViewById(R.id.overlay_category_title)).getText().toString());
                    for(ModuleCategory item:ModuleCategory.values())
                        assertEquals(item==category,first.panel().findViewById(item.viewId).isSelected());
                }
                return null;
            });
            assertEquals(before,GameHostReport.read(GameHostReport.stageFile(context())));
            MinecraftBootstrap.onMainThread(()->{
                first.panel().findViewById(R.id.overlay_close).performClick();assertNull(first.panel());assertEquals(View.VISIBLE,first.chip().getVisibility());
                first.chip().performClick();
                assertEquals("Misc",((TextView)first.panel().findViewById(R.id.overlay_category_title)).getText().toString());
                return null;
            });
            Notification notification=null;
            for(android.service.notification.StatusBarNotification n:context().getSystemService(NotificationManager.class).getActiveNotifications())
                if(n.getId()==OverlayService.NOTIFICATION)notification=n.getNotification();
            assertNotNull(notification);assertTrue((notification.flags&Notification.FLAG_ONGOING_EVENT)!=0);
            assertNotNull(notification.actions);assertTrue(notification.actions[0].actionIntent.isImmutable());
            notification.actions[0].actionIntent.send();await(OverlayService::stopped);
            MinecraftBootstrap.onMainThread(()->{assertNull(first.chip());assertNull(first.panel());return null;});
        }finally{cleanup();}
    }
    @Test public void reflowAndPermissionRevocationRemoveBothWindows()throws Exception{
        permission(true);
        try(ActivityScenario<ControlsActivity> scenario=ActivityScenario.launch(ControlsActivity.intent(context()))){
            scenario.onActivity(a->a.startForegroundService(new Intent(a,OverlayService.class).setAction(OverlayService.START)));await(OverlayService::running);
            OverlayWindow window=MinecraftBootstrap.onMainThread(OverlayService::activeWindows);
            MinecraftBootstrap.onMainThread(()->{
                window.chip().performClick();WindowManager.LayoutParams params=(WindowManager.LayoutParams)window.panel().getLayoutParams();
                params.x=100000;params.y=100000;window.reflow();assertTrue(params.x<100000&&params.y<100000);return null;
            });
            permission(false);await(OverlayService::stopped);
            MinecraftBootstrap.onMainThread(()->{assertNull(window.chip());assertNull(window.panel());window.close();return null;});
        }finally{cleanup();}
    }
    @Test public void serviceIsPrivateUiProcessNotAnExportedEngineComponent()throws Exception{
        ServiceInfo info=context().getPackageManager().getServiceInfo(new ComponentName(context(),OverlayService.class),0);
        assertFalse(info.exported);assertEquals(context().getPackageName(),info.processName);
        assertTrue((info.flags&ServiceInfo.FLAG_STOP_WITH_TASK)!=0);
        assertEquals(context().getPackageName(),android.app.Application.getProcessName());assertTrue(Process.myPid()>0);
    }
}
