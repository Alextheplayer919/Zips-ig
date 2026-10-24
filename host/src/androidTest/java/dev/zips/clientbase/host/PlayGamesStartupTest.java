package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Production invocation seam with our own demo class, never fake Google or Minecraft classes. */
@RunWith(AndroidJUnit4.class)
public final class PlayGamesStartupTest {
    private final String fixture = "dev.zips.clientbase.guest.StartupFixture";
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    @Test public void guestInitializerReceivesActualApplicationOnMainWithGuestCcl() throws Exception {
        ClassLoader loader = GuestRuntime.load(context()).loader;
        MinecraftBootstrap.onMainThread(() -> {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Application app = (Application) context().getApplicationContext();
            PlayGamesStartup.invoke(app, loader, fixture);
            Class<?> type = loader.loadClass(fixture);
            assertSame(app, type.getMethod("application").invoke(null));
            assertSame(loader, type.getMethod("loader").invoke(null));
            assertSame(original, Thread.currentThread().getContextClassLoader());
            return null;
        });
    }
    @Test public void wrongOwnerSignatureAndMissingClassAreBlocked() throws Exception {
        ClassLoader loader = GuestRuntime.load(context()).loader;
        MinecraftBootstrap.onMainThread(() -> {
            Application app = (Application) context().getApplicationContext();
            for (String name : new String[] {"java.lang.String", fixture + "$WrongSignature", fixture + "Missing"}) {
                try { PlayGamesStartup.invoke(app, loader, name); fail("Unexpected initializer accepted: " + name); }
                catch (IOException | ClassNotFoundException expected) { }
            }
            return null;
        });
    }
    @Test public void failurePropagatesAndRestoresThreadClassLoader() throws Exception {
        ClassLoader loader = GuestRuntime.load(context()).loader;
        MinecraftBootstrap.onMainThread(() -> {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            try {
                PlayGamesStartup.invoke((Application) context().getApplicationContext(), loader, fixture + "$Failing");
                fail("SDK failure swallowed");
            } catch (InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
                assertEquals("fixture SDK startup failed", expected.getCause().getMessage());
            }
            assertSame(original, Thread.currentThread().getContextClassLoader());
            return null;
        });
    }
    @Test public void workerThreadCannotRunSdkStartup() throws Exception {
        try {
            PlayGamesStartup.invoke((Application) context().getApplicationContext(), GuestRuntime.load(context()).loader, fixture);
            fail("Worker startup accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("main thread")); }
    }
}
