package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.os.Process;
import android.view.LayoutInflater;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FrameworkActivityTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void frameworkCreatesGuestWithRealHostIdentityResourcesThemeAndJni() {
        FrameworkActivityRuntime.clearEvents();
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(FrameworkActivityRuntime.fixtureIntent(context()))) {
            scenario.onActivity(this::assertEnvironment);
            assertOrder("factoryCreated", "attached", "onCreate", "onStart", "onResume");
        }
        assertTrue(FrameworkActivityRuntime.events().toString(), FrameworkActivityRuntime.events().contains("onDestroy"));
    }

    @Test public void recreationKeepsOneGuestLoaderAndNativeOwner() {
        FrameworkActivityRuntime.clearEvents();
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(FrameworkActivityRuntime.fixtureIntent(context()))) {
            final ClassLoader[] original = new ClassLoader[1];
            scenario.onActivity(a -> { assertEnvironment(a); original[0] = a.getClass().getClassLoader(); });
            scenario.recreate();
            scenario.onActivity(a -> { assertEnvironment(a); assertSame(original[0], a.getClass().getClassLoader()); });
            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue(FrameworkActivityRuntime.events().contains("onPause"));
            assertTrue(FrameworkActivityRuntime.events().contains("onStop"));
            scenario.moveToState(Lifecycle.State.RESUMED);
        }
    }

    @Test public void realSurfaceIsCreatedAndDestroyedByFramework() throws Exception {
        FrameworkActivityRuntime.clearEvents();
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(FrameworkActivityRuntime.fixtureIntent(context()))) {
            long deadline = android.os.SystemClock.uptimeMillis() + 8000;
            while ((!FrameworkActivityRuntime.events().contains("surfaceCreated")
                    || !FrameworkActivityRuntime.events().contains("SURFACE_CREATED (not title screen)"))
                    && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(50); // bounded test-only wait for actual WindowManager/Surface delivery
            }
            assertTrue(FrameworkActivityRuntime.events().toString(), FrameworkActivityRuntime.events().contains("surfaceCreated"));
            assertTrue(FrameworkActivityRuntime.events().toString(), FrameworkActivityRuntime.events().contains("SURFACE_CREATED (not title screen)"));
            scenario.onActivity(a -> {
                assertTrue(((android.view.SurfaceView) a.findViewById(android.R.id.custom)).getHolder().getSurface().isValid());
                assertNotNull(a.getWindow().getDecorView().getWindowToken());
            });
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(FrameworkActivityRuntime.events().toString(), FrameworkActivityRuntime.events().contains("surfaceDestroyed"));
        assertTrue(FrameworkActivityRuntime.events().toString(), FrameworkActivityRuntime.events().contains("SURFACE_DESTROYED"));
    }

    @Test public void factoryRejectsWrongComponentIdentity() throws Exception {
        try {
            new HostComponentFactory().instantiateActivity(context().getClassLoader(),
                    HostedGuestPlaceholderActivity.class.getName(), new Intent());
            fail("Missing host component accepted");
        } catch (InstantiationException expected) { assertTrue(expected.getCause() instanceof SecurityException); }
        // Activity constructors allocate a Handler: mirror the framework main-thread contract.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Activity normal = new HostComponentFactory().instantiateActivity(context().getClassLoader(),
                        MainActivity.class.getName(), new Intent());
                assertEquals(MainActivity.class, normal.getClass());
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test public void activityResourceConfigurationOverrideIsAppliedOnceBeforeAccess() throws Exception {
        Context host = context();
        GuestRuntime.LoadedGuest guest = GuestRuntime.load(host);
        ActivityResourceContext wrapped = new ActivityResourceContext(host,
                host.createPackageContext(GuestRuntime.GUEST_PACKAGE, 0), guest.loader, android.R.style.Theme_Material_NoActionBar);
        Configuration config = new Configuration(); config.fontScale = 1.4f;
        wrapped.applyOverrideConfiguration(config);
        assertEquals(1.4f, wrapped.getResources().getConfiguration().fontScale, 0.001f);
        assertSame(guest.loader, wrapped.getClassLoader());
        try { wrapped.applyOverrideConfiguration(config); fail("Late/repeated override accepted"); }
        catch (IllegalStateException expected) { }
    }

    private void assertEnvironment(Activity activity) {
        try {
            assertEquals(FrameworkActivityRuntime.FIXTURE, activity.getClass().getName());
            GuestRuntime.LoadedGuest guest = GuestRuntime.load(context());
            assertSame(guest.loader, activity.getClass().getClassLoader());
            assertSame(guest.loader, activity.getClassLoader());
            assertSame(guest.loader, Class.forName("androidx.appcompat.app.AppCompatActivity", false, guest.loader).getClassLoader());
            assertSame(guest.loader, Class.forName("androidx.lifecycle.LifecycleRegistry", false, guest.loader).getClassLoader());
            assertSame(guest.loader, Class.forName("androidx.lifecycle.Lifecycling", false, guest.loader).getClassLoader());
            assertEquals(context().getPackageName(), activity.getPackageName());
            assertEquals(context().getApplicationInfo().uid, activity.getApplicationInfo().uid);
            assertEquals(context().getFilesDir(), activity.getFilesDir());
            assertSame(context().getApplicationContext(), activity.getApplication());
            assertEquals(HostedGuestPlaceholderActivity.class.getName(), activity.getComponentName().getClassName());
            assertEquals(Process.myPid(), activity.getClass().getMethod("nativePid").invoke(activity));
            assertEquals(Process.myUid(), activity.getClass().getMethod("nativeUid").invoke(activity));
            assertNotEquals(guest.installedUid, Process.myUid());
            assertEquals("Guest resource loaded from the separate demo APK.", ((TextView) activity.findViewById(android.R.id.text1)).getText().toString());
            TypedArray accent = activity.obtainStyledAttributes(new int[] {android.R.attr.colorAccent});
            try { assertEquals(0xffcc44aa, accent.getColor(0, 0)); } finally { accent.recycle(); }
            assertSame(guest.loader, LayoutInflater.from(activity).getContext().getClassLoader());
            assertNotNull(activity.getWindow());
            assertNotNull(activity.getWindow().getDecorView());
            Configuration config = new Configuration(); config.fontScale = 1.2f;
            Context configured = activity.createConfigurationContext(config);
            assertSame(guest.loader, configured.getClassLoader());
            assertEquals(1.2f, configured.getResources().getConfiguration().fontScale, 0.001f);
            int banner = configured.getResources().getIdentifier("guest_banner", "string", GuestRuntime.GUEST_PACKAGE);
            assertEquals("Guest resource loaded from the separate demo APK.", configured.getString(banner));
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private void assertOrder(String... expected) {
        List<String> events = FrameworkActivityRuntime.events(); int previous = -1;
        for (String event : expected) { int at = events.indexOf(event); assertTrue(events.toString(), at > previous); previous = at; }
    }
}
