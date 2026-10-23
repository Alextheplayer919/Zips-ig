package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.zips.clientbase.contract.ActivityEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercise the actual production callback's private outer/inner calls, without fake game classes. */
@RunWith(AndroidJUnit4.class)
public final class WrapperLoaderFamilyTest {
    @Test public void childObserverAndAnonymousCallbackStayTogetherEvenWhenParentCopyExists() throws Exception {
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String root = HostedSurfaceObserver.class.getName();
        Class<?> parentInner = Class.forName(root + "$1", false, host.getClassLoader());
        BootstrapClassLoader loader = new BootstrapClassLoader(host.getApplicationInfo().sourceDir,
                host.getCodeCacheDir().getAbsolutePath(), host.getApplicationInfo().nativeLibraryDir,
                host.getClassLoader(), (name, path) -> {});
        Class<?> childOuter = Class.forName(root, true, loader);
        Class<?> childInner = Class.forName(root + "$1", false, loader);
        assertSame(loader, childOuter.getClassLoader()); assertSame(loader, childInner.getClassLoader());
        assertNotSame(parentInner, childInner); assertNotSame(HostedSurfaceObserver.class, childOuter);
        assertSame(ActivityEnvironment.class, loader.loadClass(ActivityEnvironment.class.getName()));
        MinecraftBootstrap.onMainThread(() -> {
            Activity activity = new Activity(); List<String> events = new ArrayList<>();
            ActivityEnvironment environment = new ActivityEnvironment() {
                @Override public Context attach(Context base) { return base; }
                @Override public int mapTheme(int id) { return id; }
                @Override public void event(Activity instance, String event) { assertSame(activity, instance); events.add(event); }
            };
            Object observer = childOuter.getConstructor(Activity.class, ActivityEnvironment.class).newInstance(activity, environment);
            SurfaceHolder.Callback callback = (SurfaceHolder.Callback) childOuter.getMethod("callback").invoke(observer);
            assertSame(childInner, callback.getClass());
            // This construction/private access is the exact kind that crashed on the phone.
            childOuter.getMethod("observe", android.view.View.class).invoke(observer, new SurfaceView(host));
            callback.surfaceCreated(null); callback.surfaceChanged(null, 0, 40, 30); callback.surfaceDestroyed(null);
            assertEquals(Arrays.asList("SURFACE_CREATED (not title screen)", "SURFACE_CHANGED 40x30", "SURFACE_DESTROYED"), events);
            return null;
        });
    }

    @Test public void ownershipIncludesOnlyExactHostFamiliesNotOtherHostOrContractClasses() {
        String root = "dev.zips.clientbase.host.MinecraftHostedActivity";
        for (String name : new String[] {root, root + "$1", root + "$Nested$1", "dev.zips.clientbase.host.HostedSurfaceObserver$1"}) {
            assertTrue(name, BootstrapClassLoader.ownsHostClass(name));
        }
        for (String name : new String[] {root + "Other", root + ".Other", "dev.zips.clientbase.host.GameHostController",
                "dev.zips.clientbase.contract.ActivityEnvironment"}) assertFalse(name, BootstrapClassLoader.ownsHostClass(name));
    }

    @Test public void activityTimelineDropsRepeatedDexButKeepsLookupAndFailureCause() {
        String verbose = "JVM_INITIALIZATION_IN_PROGRESS\nActual GameActivity DEX:\n" + "x".repeat(6000)
                + "\nNative library lookups (not completion receipts):\nc++_shared -> /first\nminecraftpe -> /engine";
        assertEquals("NATIVE_LIBRARY_LOOKUP (not load completion)\nminecraftpe -> /engine", GameHostReport.compactStage(verbose));
        assertTrue(GameHostReport.compactStage("JAVA_BOOTSTRAP_PREFLIGHT\n" + verbose).length() < 150);
        String error = "ACTIVITY_FACTORY_BLOCKED\njava.lang.IllegalAccessError: wrapper helper";
        assertEquals(error, GameHostReport.compactStage(error));
    }
}
