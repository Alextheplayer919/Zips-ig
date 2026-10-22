package dev.zips.clientbase.host;

import android.content.Context;
import android.os.Process;
import android.view.View;
import android.widget.TextView;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class GuestRuntimeTest {
    @Test public void installedGuestCodeAndJniRunAsHost() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GuestRuntime.LoadedGuest loaded = GuestRuntime.load(context);
        assertEquals(Process.myPid(), loaded.entry.processId());
        assertEquals(Process.myUid(), loaded.entry.userId());
        assertNotEquals(loaded.installedUid, loaded.entry.userId());
        assertSame(loaded.loader, loaded.entry.getClass().getClassLoader());
        assertNotSame(context.getClassLoader(), loaded.loader);
        assertTrue(loaded.apkCount >= 1);
        assertEquals("Guest resource loaded from the separate demo APK.",
                loaded.resources.getString(loaded.resources.getIdentifier(
                        "guest_banner", "string", GuestRuntime.GUEST_PACKAGE)));
    }

    @Test public void guestViewAndLoaderSurviveHostActivityRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::assertGuestView);
            scenario.recreate();
            scenario.onActivity(this::assertGuestView);
        }
    }

    private void assertGuestView(MainActivity activity) {
        try {
            activity.findViewById(dev.zips.clientbase.host.R.id.load_demo).performClick();
            TextView status = activity.findViewById(dev.zips.clientbase.host.R.id.load_status);
            assertTrue(status.getText().toString(), status.getText().toString().startsWith("PASS:"));
            FrameLayout panel = activity.findViewById(dev.zips.clientbase.host.R.id.guest_panel);
            assertEquals(1, panel.getChildCount());
            GuestRuntime.LoadedGuest first = GuestRuntime.load(activity);
            assertSame(first, GuestRuntime.load(activity));
            View view = first.entry.createView(activity, first.resources);
            assertTrue(view instanceof TextView);
            assertTrue(((TextView) view).getText().toString().contains("Guest resource loaded"));
            assertEquals(Process.myPid(), first.entry.processId());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
