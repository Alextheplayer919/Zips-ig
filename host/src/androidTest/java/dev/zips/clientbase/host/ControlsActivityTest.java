package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Process;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ControlsActivityTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    @Test public void controlsHaveIndependentTaskUiIdentityAndSurviveRecreation() throws Exception {
        Context c=context();ActivityInfo info=c.getPackageManager().getActivityInfo(new ComponentName(c,ControlsActivity.class),0);
        assertFalse(info.exported);assertEquals(c.getPackageName(),info.processName);
        assertEquals(c.getPackageName()+".controls",info.taskAffinity);
        final int[] launcherTask={-1};
        try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)){
            main.onActivity(a -> {launcherTask[0]=a.getTaskId();assertNotNull(a.findViewById(R.id.open_controls));});
            try(ActivityScenario<ControlsActivity> controls=ActivityScenario.launch(ControlsActivity.intent(c))){
                controls.onActivity(a -> {
                    assertNotEquals(launcherTask[0],a.getTaskId());assertEquals(c.getPackageName(),android.app.Application.getProcessName());
                    assertTrue(Process.myPid()>0);assertNotNull(a.findViewById(R.id.memory_refresh));
                    ((TextView)a.findViewById(R.id.memory_report)).setText("saved fixture snapshot");
                });
                controls.recreate();controls.onActivity(a -> assertEquals("saved fixture snapshot",((TextView)a.findViewById(R.id.memory_report)).getText().toString()));
            }
        }
    }
    @Test public void developerToolsAreCollapsedButRetained() {
        try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)){
            main.onActivity(a -> {
                android.view.View demo=a.findViewById(R.id.load_demo);assertFalse(demo.isShown());
                a.findViewById(R.id.developer_tools).performClick();assertTrue(demo.isShown());
                a.findViewById(R.id.developer_tools).performClick();assertFalse(demo.isShown());
            });
        }
    }
}
