package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ModuleRegistryTest {
    @Test public void registrationsAreUniqueImmutableAndDoNotPretendBackendReadiness() {
        assertEquals(2,ModuleRegistry.all().size());
        java.util.Set<String> ids=new java.util.HashSet<>();
        for(ModuleRegistry.Entry entry:ModuleRegistry.all()) {
            assertTrue(ids.add(entry.id));assertTrue(entry.blocker.startsWith("Unavailable"));
        }
        assertEquals("MotionFly",ModuleRegistry.forCategory(ModuleCategory.MOVEMENT).get(0).name);
        assertEquals("KillAura",ModuleRegistry.forCategory(ModuleCategory.COMBAT).get(0).name);
        assertTrue(ModuleRegistry.forCategory(ModuleCategory.VISUAL).isEmpty());
        assertTrue(ModuleRegistry.forCategory(ModuleCategory.MISC).isEmpty());
        try{ModuleRegistry.all().clear();fail("Mutable registrations");}catch(UnsupportedOperationException expected){}
        try{ModuleRegistry.forCategory(ModuleCategory.MOVEMENT).clear();fail("Mutable category");}catch(UnsupportedOperationException expected){}
    }
    @Test public void controlsKeepTechnicalReportsOutsideTheModuleMenu() {
        try(ActivityScenario<ControlsActivity> controls=ActivityScenario.launch(ControlsActivity.class)) {
            controls.onActivity(a->{
                assertNotNull(a.findViewById(R.id.memory_refresh));assertNotNull(a.findViewById(R.id.memory_copy));
                assertTrue(((TextView)a.findViewById(R.id.memory_report)).getText().length()>0);
            });
        }
    }
}
