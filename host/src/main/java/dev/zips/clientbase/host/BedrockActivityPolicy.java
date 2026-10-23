package dev.zips.clientbase.host;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import java.io.IOException;

/** Fail closed on the actual uploaded manifest contract; this is not signature authentication. */
final class BedrockActivityPolicy {
    static final String GAME = "com.mojang.minecraftpe", MAIN = GAME + ".MainActivity";
    static void verify(PackageInfo pkg, ApplicationInfo app, ActivityInfo activity) throws IOException {
        if (!GAME.equals(app.packageName) || !"1.26.50.4".equals(pkg.versionName) || pkg.getLongVersionCode() != 972605004L
                || !"android.app.Application".equals(app.className)
                || !"androidx.core.app.CoreComponentFactory".equals(app.appComponentFactory)
                || !MAIN.equals(activity.name) || !GAME.equals(activity.packageName)
                || activity.getThemeResource() != 0x7f0e0007 || activity.launchMode != ActivityInfo.LAUNCH_SINGLE_TASK
                || activity.screenOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || activity.configChanges != 0x40001fff || activity.metaData == null
                || !"minecraftpe".equals(activity.metaData.getString("android.app.lib_name"))) {
            throw new IOException("Installed Bedrock Activity manifest differs from the reviewed startup contract");
        }
    }
}
