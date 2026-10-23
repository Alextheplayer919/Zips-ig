package dev.zips.clientbase.host;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import dev.zips.clientbase.contract.ActivityEnvironment;
import dev.zips.clientbase.contract.HostedActivity;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.function.Consumer;

/** One prepared loader/session in :game_host only. Never initialized by a restored component. */
final class GameActivityRuntime {
    static final String WRAPPER = "dev.zips.clientbase.host.MinecraftHostedActivity";
    private static MinecraftBootstrap.Prepared prepared;
    private static Context resources;
    private static int theme;
    private static Consumer<String> progress;
    private static boolean created;

    static void requireGameProcess(Context c) {
        if (!(c.getPackageName() + ":game_host").equals(Application.getProcessName())) {
            throw new SecurityException("Original game Activity must run in the dedicated host process");
        }
    }
    static void prepare(Context context, Consumer<String> report) throws Exception {
        requireGameProcess(context);
        if (prepared != null) throw new IOException("Session already prepared; restart the game process for another attempt");
        String arch = System.getProperty("os.arch", "unknown");
        if (!Process.is64Bit() || !("aarch64".equals(arch) || "arm64".equals(arch))) throw new IOException("Original game requires ARM64");
        if (context.checkSelfPermission(android.Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) throw new IOException("Host INTERNET permission absent");
        PackageManager pm = context.getPackageManager();
        ApplicationInfo game = pm.getApplicationInfo(BedrockActivityPolicy.GAME, PackageManager.GET_META_DATA);
        PackageInfo pkg = pm.getPackageInfo(BedrockActivityPolicy.GAME, 0);
        ActivityInfo activity = pm.getActivityInfo(new ComponentName(BedrockActivityPolicy.GAME, BedrockActivityPolicy.MAIN), PackageManager.GET_META_DATA);
        BedrockActivityPolicy.verify(pkg, game, activity);
        if (game.splitSourceDirs != null && game.splitSourceDirs.length != 0) throw new IOException("This first Activity adapter requires the reported single-APK layout");
        report.accept("Verifying all supplied manifest/DEX/resource bytes before defining game classes");
        BedrockInputPins.verify(game.sourceDir);
        MinecraftBootstrap.Prepared candidate = MinecraftBootstrap.prepare(context, game, pkg, Collections.singletonList(game.sourceDir), report);
        if (Modifier.isFinal(candidate.mainClass.getModifiers())) throw new IOException("Original MainActivity cannot be subclassed");
        Context resourceOnly = context.createPackageContext(BedrockActivityPolicy.GAME, 0);
        // Validate theme/resource ownership before the framework enters original code.
        if (!BedrockActivityPolicy.GAME.equals(resourceOnly.getResources().getResourcePackageName(activity.getThemeResource()))) throw new IOException("Theme belongs to unexpected resource package");
        resources = resourceOnly; theme = activity.getThemeResource(); progress = report;
        ClientBaseApplication.current().installGameResources(resourceOnly, candidate.loader);
        prepared = candidate;
        report.accept("GAME_ENVIRONMENT_PREPARED\nExact supplied startup bytes and engine matched. Original providers are NOT installed; later SDK failures remain possible.");
    }
    static Activity create(Context context) throws Exception {
        requireGameProcess(context);
        if (prepared == null || created) throw new IOException("No fresh prepared game session (no automatic relaunch/recreation)");
        created = true;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(prepared.loader);
        try {
            progress.accept("ORIGINAL_CLASS_INITIALIZATION_AND_CONSTRUCTION_ENTERED");
            Class<?> wrapper = Class.forName(WRAPPER, true, prepared.loader);
            if (wrapper.getClassLoader() != prepared.loader || wrapper.getSuperclass() != prepared.mainClass
                    || !HostedActivity.class.isAssignableFrom(wrapper)) throw new IOException("Wrapper/original/contract loader identity mismatch");
            Activity activity = (Activity) wrapper.getDeclaredConstructor().newInstance();
            ((HostedActivity) activity).configureHost(new ActivityEnvironment() {
                @Override public Context attach(Context base) { return new ActivityResourceContext(base, resources, prepared.loader, theme); }
                @Override public int mapTheme(int requested) { return requested == android.R.style.Theme_Material_NoActionBar ? theme : requested; }
                @Override public void event(Activity current, String event) { progress.accept(event); }
            });
            progress.accept("FRAMEWORK_ACTIVITY_CREATED (not attached or title-screen proof)");
            return activity;
        } catch (Exception | LinkageError e) { progress.accept("ACTIVITY_FACTORY_BLOCKED\n" + MinecraftBootstrap.describeFailure(e)); throw e; }
        finally { Thread.currentThread().setContextClassLoader(previous); }
    }
}
