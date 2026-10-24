package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.widget.FrameLayout;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.Serializable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class GameSupportActivitiesTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private void awaitHostStopped(Context c) {
        long deadline = SystemClock.uptimeMillis() + 5000L;
        while (SystemClock.uptimeMillis() < deadline && ModuleClient.isGameHostProcessRunning(c)) {
            SystemClock.sleep(40L);
        }
    }

    @Test public void privateGameHostSupportActivitiesAreNonExportedAndPreserveForResultContract() throws Exception {
        Context c = context();
        PackageManager pm = c.getPackageManager();
        for (String name : GameSupportActivities.SUPPORTED) {
            ActivityInfo info = pm.getActivityInfo(new ComponentName(c.getPackageName(), name), 0);
            assertFalse(name, info.exported);
            assertEquals(name, c.getPackageName() + ":game_host", info.processName);
            assertEquals(name, c.getApplicationInfo().uid, info.applicationInfo.uid);
        }
        // WebKitWebViewController is started via startActivityForResult(..., 8053) by BrowserLaunchActivity;
        // singleTask/singleInstance would make Android ActivityStarter cancel the result immediately.
        ActivityInfo webViewInfo = pm.getActivityInfo(
                new ComponentName(c.getPackageName(), GameSupportActivities.XAL_WEBVIEW), 0);
        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, webViewInfo.launchMode);
    }

    @Test public void supportActivitiesRejectUiProcessAndWrongComponentInFactory() throws Exception {
        Context c = context();
        HostComponentFactory factory = new HostComponentFactory();
        for (String name : GameSupportActivities.SUPPORTED) {
            for (Intent intent : new Intent[] {
                null,
                new Intent(),
                new Intent().setComponent(new ComponentName("com.mojang.minecraftpe", name)),
                new Intent().setComponent(new ComponentName(c.getPackageName(), name))
            }) {
                try {
                    factory.instantiateActivity(c.getClassLoader(), name, intent);
                    fail("Instantiated support Activity in UI process / wrong component: " + name);
                } catch (InstantiationException expected) {
                    assertTrue(expected.getCause() instanceof SecurityException);
                }
            }
        }
        assertFalse(GameSupportActivities.isSupported("com.microsoft.xal.browser.IntentHandler"));
        assertFalse(GameSupportActivities.isSupported("com.mojang.minecraftpe.MainActivity"));
    }

    @Test public void guestLoaderExtrasAndWebViewDomStorageHelperWorkOnRealAndroidFramework() throws Exception {
        Context c = context();
        ClassLoader guestLoader = GuestRuntime.load(c).loader;
        Class<?> enumType = Class.forName("dev.zips.clientbase.guest.GuestAuthExtraFixture", true, guestLoader);
        assertSame(guestLoader, enumType.getClassLoader());
        Serializable enumConstant = (Serializable) enumType.getField("NORMAL").get(null);

        Intent source = new Intent();
        Bundle extras = new Bundle();
        extras.putSerializable("SHOW_TYPE", enumConstant);
        source.putExtras(extras);

        Parcel parcel = Parcel.obtain();
        final Intent roundTripped;
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            roundTripped = Intent.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
        // Mirror ActivityThread.performLaunchActivity setting LoadedApk PathClassLoader on Intent extras.
        roundTripped.setExtrasClassLoader(c.getClassLoader());
        GameSupportActivities.prepareActivityState(roundTripped, null, guestLoader);
        Object unmarshalled = roundTripped.getExtras().get("SHOW_TYPE");
        assertSame(enumConstant, unmarshalled);

        MinecraftBootstrap.onMainThread(() -> {
            FrameLayout container = new FrameLayout(c);
            WebView webView = new WebView(c);
            try {
                webView.getSettings().setDomStorageEnabled(false);
                container.addView(webView);
                assertEquals(1, GameSupportActivities.enableWebViewDomStorage(container));
                assertTrue(webView.getSettings().getDomStorageEnabled());
            } finally {
                container.removeAllViews();
                webView.destroy();
            }
            return null;
        });
    }

    @Test public void repeatedUnpreparedGameHostLaunchesAbortCleanlyWithoutServiceQuarantine() throws Exception {
        Context c = context();
        awaitHostStopped(c);
        String stageBefore = GameHostReport.read(GameHostReport.stageFile(c));
        ClassLoader guestLoader = GuestRuntime.load(c).loader;
        Class<?> enumType = Class.forName("dev.zips.clientbase.guest.GuestAuthExtraFixture", true, guestLoader);
        Serializable guestOnlyExtra = (Serializable) enumType.getField("NORMAL").get(null);

        // Simulate Android recreating :game_host Activities from a foreground task without a
        // prepared session, including guest-DEX extras (like XAL ShowUrlType) absent on PathClassLoader.
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            for (String component : new String[] {
                GameSupportActivities.XAL_WEBVIEW,
                GameSupportActivities.XAL_BROWSER_LAUNCH,
                GameActivityRuntime.WRAPPER
            }) {
                main.onActivity(activity -> {
                    Intent intent = new Intent()
                            .setComponent(new ComponentName(c.getPackageName(), component))
                            .putExtra("SHOW_TYPE", guestOnlyExtra);
                    activity.startActivity(intent);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                SystemClock.sleep(250L);
            }
        }

        // Verify :game_host service binding still succeeds right afterward (not quarantined)
        // and stageFile was not clobbered by AbortedSessionActivity.
        CountDownLatch latch = new CountDownLatch(1);
        final ModuleState[] reply = {null};
        ModuleClient client = MinecraftBootstrap.onMainThread(() -> {
            ModuleClient mc = new ModuleClient(c, ModuleProtocol.QUERY, false, false, true, false, false,
                    /*requireRunningHost=*/false, state -> {
                        reply[0] = state;
                        latch.countDown();
                    });
            mc.start();
            return mc;
        });
        try {
            assertTrue("Expected :game_host service binding to succeed without quarantine",
                    latch.await(10, TimeUnit.SECONDS));
            assertNotNull(reply[0]);
            assertFalse(reply[0].message, reply[0].message.contains("binding rejected"));
            assertEquals(stageBefore, GameHostReport.read(GameHostReport.stageFile(c)));
        } finally {
            MinecraftBootstrap.onMainThread(() -> { client.cancel(); return null; });
            awaitHostStopped(c);
        }
    }

    @Test public void warmupActivityWakesGameHostAndHandsOffLiveBinderWithoutBindService() throws Exception {
        Context c = context();
        awaitHostStopped(c);

        ActivityInfo warmupInfo = c.getPackageManager().getActivityInfo(
                new ComponentName(c.getPackageName(), GameHostWarmupActivity.class.getName()), 0);
        assertFalse(warmupInfo.exported);
        assertEquals(c.getPackageName() + ":game_host", warmupInfo.processName);

        CountDownLatch warmupLatch = new CountDownLatch(1);
        final int[] childPid = {0};
        final int[] childUid = {-1};
        final android.os.IBinder[] warmBinder = {null};

        android.os.Messenger replyTo = MinecraftBootstrap.onMainThread(() ->
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), message -> {
                    Bundle data = message.getData();
                    if (message.what == GameHostWarmupActivity.RESULT_READY && data != null) {
                        childPid[0] = data.getInt("pid", 0);
                        childUid[0] = data.getInt("uid", -1);
                        warmBinder[0] = data.getBinder("binder");
                        warmupLatch.countDown();
                    }
                    return true;
                })));

        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(activity ->
                    activity.startActivity(GameHostWarmupActivity.intent(activity, 77, replyTo)));
            assertTrue("Expected GameHostWarmupActivity Messenger handoff",
                    warmupLatch.await(10, TimeUnit.SECONDS));
            assertTrue("Expected separate :game_host PID",
                    childPid[0] > 0 && childPid[0] != android.os.Process.myPid());
            assertEquals(android.os.Process.myUid(), childUid[0]);
            assertNotNull(warmBinder[0]);
            assertTrue(warmBinder[0].isBinderAlive());

            assertTrue(GameHostLiveBinder.attach(warmBinder[0], null));
            assertTrue(ModuleClient.isGameHostProcessRunning(c));

            // Prove ModuleClient and MemoryClient communicate over GameHostLiveBinder directly.
            CountDownLatch modLatch = new CountDownLatch(1);
            final ModuleState[] modReply = {null};
            ModuleClient mc = MinecraftBootstrap.onMainThread(() -> {
                ModuleClient m = new ModuleClient(c, ModuleProtocol.QUERY, false, false, true, false, false,
                        /*requireRunningHost=*/true, state -> {
                            modReply[0] = state;
                            modLatch.countDown();
                        });
                m.start();
                return m;
            });
            assertTrue(modLatch.await(10, TimeUnit.SECONDS));
            assertNotNull(modReply[0]);
            assertTrue(modReply[0].message, modReply[0].message.contains("No live prepared Activity session"));
            MinecraftBootstrap.onMainThread(() -> { mc.cancel(); return null; });

            CountDownLatch memLatch = new CountDownLatch(1);
            final String[] memReply = {null};
            MemoryClient mem = MinecraftBootstrap.onMainThread(() -> {
                MemoryClient client = new MemoryClient(c, report -> {
                    memReply[0] = report;
                    memLatch.countDown();
                });
                client.start();
                return client;
            });
            assertTrue(memLatch.await(10, TimeUnit.SECONDS));
            assertNotNull(memReply[0]);
            assertTrue(memReply[0], memReply[0].contains("No live prepared Activity session"));
            MinecraftBootstrap.onMainThread(() -> { mem.cancel(); return null; });
        } finally {
            GameHostWarmupActivity.dismissIfOpen();
            GameHostLiveBinder.clear();
            try { c.stopService(new Intent(c, GameHostService.class)); }
            catch (Exception ignored) {}
            if (childPid[0] > 0 && childPid[0] != android.os.Process.myPid()) {
                android.os.Process.killProcess(childPid[0]);
            }
            awaitHostStopped(c);
        }
    }
}
