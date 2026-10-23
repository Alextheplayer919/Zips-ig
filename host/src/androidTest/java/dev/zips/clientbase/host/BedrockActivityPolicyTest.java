package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class BedrockActivityPolicyTest {
    private PackageInfo pkg() { PackageInfo p = new PackageInfo(); p.versionName = "1.26.50.4"; p.setLongVersionCode(972605004L); return p; }
    private ApplicationInfo app() {
        ApplicationInfo a = new ApplicationInfo(); a.packageName = "com.mojang.minecraftpe";
        a.className = "android.app.Application"; a.appComponentFactory = "androidx.core.app.CoreComponentFactory"; return a;
    }
    private ActivityInfo activity() {
        ActivityInfo a = new ActivityInfo(); a.packageName = "com.mojang.minecraftpe"; a.name = a.packageName + ".MainActivity";
        a.theme = 0x7f0e0007; a.launchMode = ActivityInfo.LAUNCH_SINGLE_TASK;
        a.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; a.configChanges = 0x40001fff;
        a.metaData = new Bundle(); a.metaData.putString("android.app.lib_name", "minecraftpe"); return a;
    }
    @Test public void reviewedManifestContractAcceptedAndChangedMetadataRejected() throws Exception {
        BedrockActivityPolicy.verify(pkg(), app(), activity());
        ActivityInfo changed = activity(); changed.metaData.putString("android.app.lib_name", "main");
        try { BedrockActivityPolicy.verify(pkg(), app(), changed); fail("Changed library accepted"); } catch (IOException expected) { }
        ApplicationInfo different = app(); different.className = "example.CustomApplication";
        try { BedrockActivityPolicy.verify(pkg(), different, activity()); fail("Unreviewed Application accepted"); } catch (IOException expected) { }
        PackageInfo wrong = pkg(); wrong.setLongVersionCode(972605005L);
        try { BedrockActivityPolicy.verify(wrong, app(), activity()); fail("Changed version accepted"); } catch (IOException expected) { }
    }
    @Test public void gameComponentIsPrivateSameUidSeparateProcessWithNativeMetadata() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ActivityInfo a = c.getPackageManager().getActivityInfo(new ComponentName(c, GameActivityPlaceholder.class), PackageManager.GET_META_DATA);
        assertFalse(a.exported); assertEquals(c.getPackageName() + ":game_host", a.processName);
        assertEquals(c.getApplicationInfo().uid, a.applicationInfo.uid);
        assertEquals("minecraftpe", a.metaData.getString("android.app.lib_name"));
        assertEquals(0x40001fff, a.configChanges);
        assertEquals(PackageManager.PERMISSION_GRANTED, c.checkSelfPermission(android.Manifest.permission.INTERNET));
    }
    @Test public void unpreparedGameFactoryCannotExecuteInsideHostUiProcess() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            new HostComponentFactory().instantiateActivity(c.getClassLoader(), GameActivityPlaceholder.class.getName(), new Intent(c, GameActivityPlaceholder.class));
            fail("Game Activity created in UI process");
        } catch (InstantiationException expected) { assertTrue(expected.getCause() instanceof SecurityException); }
    }
    @Test public void exitIdentityCannotCrossBetweenGameAndProbeProcesses() {
        long time = System.currentTimeMillis();
        ProbeExitDiagnostics.Run game = new ProbeExitDiagnostics.Run(1234, 10000, time, "none", ":game_host");
        assertTrue(ProbeExitDiagnostics.matches(game, 1234, 10000, "dev.test:game_host", time, "dev.test"));
        assertFalse(ProbeExitDiagnostics.matches(game, 1234, 10000, "dev.test:engine_probe", time, "dev.test"));
        assertFalse(ProbeExitDiagnostics.matches(game, 1235, 10000, "dev.test:game_host", time, "dev.test"));
        try { new ProbeExitDiagnostics.Run(1234, 10000, time, "none", ":other"); fail("Unknown process accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
