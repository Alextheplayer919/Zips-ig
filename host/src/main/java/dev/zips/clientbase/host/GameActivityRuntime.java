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
    private static volatile MinecraftBootstrap.Prepared prepared;
    private static Context resources;
    private static int theme;
    private static Consumer<String> progress;
    private static volatile boolean created;

    static boolean hasCreatedActivity() { return created; }
    static boolean isPrepared() { return prepared != null; }

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
        GameSupportActivities.installLifecycleSupport(ClientBaseApplication.current(), candidate.loader);
        prepared = candidate;
        report.accept("GAME_ENVIRONMENT_PREPARED\nExact supplied startup bytes and engine matched. Original providers are NOT installed; later SDK failures remain possible.");
    }
    static android.app.Service createFirebaseDiscoveryService(Context context) throws Exception {
        requireGameProcess(context);
        if (prepared == null) throw new IOException("Firebase service requires the pinned prepared game session");
        Class<?> type = Class.forName(FirebaseStartup.DISCOVERY, true, prepared.loader);
        if (type.getClassLoader() != prepared.loader || !android.app.Service.class.isAssignableFrom(type))
            throw new IOException("Unexpected Firebase discovery service identity");
        return (android.app.Service) type.getDeclaredConstructor().newInstance();
    }
    static String moduleControl(Context context, int op,
                                boolean flyDesired, boolean auraDesired,
                                boolean attackMobs, boolean multiTarget, boolean checkHurtTime) throws Exception {
        requireGameProcess(context);
        if (op < 0 || op > 2) throw new IOException("Unknown module operation");
        MinecraftBootstrap.Prepared session = prepared;
        if (session == null || !created) throw new IOException("No live prepared Activity session");
        Class<?> bridge = Class.forName("dev.zips.clientbase.host.EngineNativeBridge", true, session.loader);
        if (bridge.getClassLoader() != session.loader) throw new IOException("Module bridge owner mismatch");
        return (String) bridge.getMethod("moduleControl",
                String.class, int.class, boolean.class, boolean.class,
                boolean.class, boolean.class, boolean.class)
                .invoke(null, session.enginePath, op, flyDesired, auraDesired,
                        attackMobs, multiTarget, checkHurtTime);
    }
    static String memorySnapshot(Context context) throws Exception {
        requireGameProcess(context);
        MinecraftBootstrap.Prepared session = prepared;
        if (session == null || !created) throw new IOException("No live prepared Activity session");
        Class<?> bridge = Class.forName("dev.zips.clientbase.host.EngineNativeBridge", true, session.loader);
        if (bridge.getClassLoader() != session.loader) throw new IOException("Memory bridge owner mismatch");
        String snapshot = (String) bridge.getMethod("memorySnapshot", String.class).invoke(null, session.enginePath);
        String build = "afa62bd3385161f1facfe36aad58ac63998f46d2";
        return (snapshot.contains("Build ID: " + build + "\n") && snapshot.contains("ELF64_HEADER_READ_OK")
                ? "ENGINE IDENTITY VERIFIED (in-memory build ID)\n" : "ENGINE IDENTITY NOT CONFIRMED — no offsets may be used\n")
                + snapshot + "\nStartup file SHA-256 pin: " + EngineInput.LIBRARY_SHA;
    }
    static Activity createSupportActivity(Context context, String className) throws Exception {
        requireGameProcess(context);
        if (!GameSupportActivities.isSupported(className)) {
            throw new SecurityException("Unsupported game support Activity: " + className);
        }
        MinecraftBootstrap.Prepared session = prepared;
        if (session == null || !created) {
            return new AbortedSessionActivity();
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(session.loader);
        try {
            Class<?> type = Class.forName(className, true, session.loader);
            if (type.getClassLoader() != session.loader || !Activity.class.isAssignableFrom(type)) {
                throw new IOException("Unexpected support Activity loader identity: " + className);
            }
            if (progress != null) progress.accept("GAME_SUPPORT_ACTIVITY_CREATED: " + className);
            return (Activity) type.getDeclaredConstructor().newInstance();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
    static Activity create(Context context) throws Exception {
        requireGameProcess(context);
        if (prepared == null || created) {
            // Finish cleanly instead of crashing :game_host on framework auto-recreation after an exit.
            return new AbortedSessionActivity();
        }
        created = true;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(prepared.loader);
        try {
            // Before construction/lifecycle: the SDK registers Application lifecycle callbacks.
            // Use the already pinned original SDK, not a parent copy or synthetic provider authority.
            // Original provider initOrder: Firebase 100, Play Games 99.
            FirebaseStartup.initialize(ClientBaseApplication.current(), prepared.loader, progress);
            PlayGamesStartup.initialize(ClientBaseApplication.current(), prepared.loader, progress);
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
