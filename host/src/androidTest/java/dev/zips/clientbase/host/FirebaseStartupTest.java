package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FirebaseStartupTest {
    private static final String FIXTURE = "dev.zips.clientbase.guest.FirebaseStartupFixture";
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    @Test public void actualInvocationSeamUsesApplicationAndRestoresCcl() throws Exception {
        ClassLoader loader = GuestRuntime.load(context()).loader;
        MinecraftBootstrap.onMainThread(() -> {
            Class<?> fixture = loader.loadClass(FIXTURE);
            fixture.getMethod("mode", String.class).invoke(null, "ok");
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Application app = (Application) context().getApplicationContext();
            FirebaseStartup.invoke(app, loader, FIXTURE, FIXTURE + "$Messaging");
            assertSame(app, fixture.getMethod("application").invoke(null));
            assertSame(loader, fixture.getMethod("loader").invoke(null));
            assertSame(previous, Thread.currentThread().getContextClassLoader());
            return null;
        });
    }
    @Test public void missingOptionsWrongDefaultMissingMessagingAndExceptionsBlock() throws Exception {
        ClassLoader loader = GuestRuntime.load(context()).loader;
        MinecraftBootstrap.onMainThread(() -> {
            Class<?> fixture = loader.loadClass(FIXTURE);
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                for (String mode : new String[] {"null", "mismatch", "missingMessaging", "throw"}) {
                    fixture.getMethod("mode", String.class).invoke(null, mode);
                    try {
                        FirebaseStartup.invoke((Application) context().getApplicationContext(), loader, FIXTURE, FIXTURE + "$Messaging");
                        fail("Accepted failed initialization: " + mode);
                    } catch (IOException | InvocationTargetException expected) {
                        assertTrue(expected instanceof IOException || expected.getCause() instanceof IllegalStateException);
                    }
                    assertSame(previous, Thread.currentThread().getContextClassLoader());
                }
            } finally { fixture.getMethod("mode", String.class).invoke(null, "ok"); }
            return null;
        });
    }
    @Test public void discoveryMetadataIsRealPrivateHostDeclarationWithOriginalThreeRegistrars() throws Exception {
        Context c = context();
        ServiceInfo info = c.getPackageManager().getServiceInfo(new ComponentName(c.getPackageName(), FirebaseStartup.DISCOVERY), PackageManager.GET_META_DATA);
        assertFalse(info.exported); assertTrue(info.directBootAware);
        assertEquals(c.getPackageName() + ":game_host", info.processName);
        assertEquals(c.getApplicationInfo().uid, info.applicationInfo.uid);
        String prefix = "com.google.firebase.components:";
        assertEquals(new HashSet<>(Arrays.asList(prefix + "com.google.firebase.messaging.FirebaseMessagingRegistrar",
                prefix + "com.google.firebase.datatransport.TransportRegistrar",
                prefix + "com.google.firebase.installations.FirebaseInstallationsRegistrar")), info.metaData.keySet());
        for (String key : info.metaData.keySet()) assertEquals("com.google.firebase.components.ComponentRegistrar", info.metaData.getString(key));
    }
    @Test public void discoveryFactoryCannotStartSdkInUiOrAcceptWrongComponent() throws Exception {
        Context c = context();
        for (Intent intent : new Intent[] {null, new Intent().setComponent(new ComponentName(c.getPackageName(), FirebaseStartup.DISCOVERY)),
                new Intent().setComponent(new ComponentName("com.mojang.minecraftpe", FirebaseStartup.DISCOVERY))}) {
            try {
                new HostComponentFactory().instantiateService(c.getClassLoader(), FirebaseStartup.DISCOVERY, intent);
                fail("SDK service instantiated in host UI / wrong component");
            } catch (InstantiationException expected) { assertTrue(expected.getCause() instanceof SecurityException); }
        }
    }
    @Test public void wrongThreadOwnerAndSignatureAreRejected() throws Exception {
        ClassLoader loader = GuestRuntime.load(context()).loader;
        try {
            FirebaseStartup.invoke((Application) context().getApplicationContext(), loader, FIXTURE, FIXTURE + "$Messaging");
            fail("Worker accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("main thread")); }
        MinecraftBootstrap.onMainThread(() -> {
            for (String name : new String[] {"java.lang.String", "dev.zips.clientbase.guest.StartupFixture", FIXTURE + "Missing"}) {
                try {
                    FirebaseStartup.invoke((Application) context().getApplicationContext(), loader, name, FIXTURE + "$Messaging");
                    fail("Bad SDK accepted");
                } catch (IOException | ReflectiveOperationException expected) { }
            }
            return null;
        });
    }
}
