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

        // Simulate Android recreating :game_host Activities twice without a prepared session;
        // previously two uncaught InstantiationExceptions within 60s triggered mBadProcesses
        // quarantine ("Game service binding rejected").
        for (String component : new String[] {
            GameSupportActivities.XAL_BROWSER_LAUNCH,
            GameActivityRuntime.WRAPPER
        }) {
            Intent intent = new Intent()
                    .setComponent(new ComponentName(c.getPackageName(), component))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(intent);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(250L);
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
}
