package dev.zips.clientbase.host;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

/** Explicit equivalent of the omitted PlayGamesInitProvider's initialization call only. */
final class PlayGamesStartup {
    private static final String SDK = "com.google.android.gms.games.PlayGamesSdk";
    static void initialize(Application application, ClassLoader loader, Consumer<String> report) throws Exception {
        GameActivityRuntime.requireGameProcess(application);
        report.accept("PLAY_GAMES_SDK_INITIALIZATION_ENTERED (original public API; not authentication)");
        try {
            invoke(application, loader, SDK);
            report.accept("PLAY_GAMES_SDK_INITIALIZATION_RETURNED (not sign-in or achievements success)");
        } catch (Exception | LinkageError error) {
            report.accept("PLAY_GAMES_SDK_INITIALIZATION_BLOCKED\n" + MinecraftBootstrap.describeFailure(error));
            throw error;
        }
    }
    // Package-private seam for our own demo fixture; production uses only the fixed SDK name above.
    static void invoke(Application application, ClassLoader loader, String name) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IOException("SDK startup requires the main thread");
        Class<?> sdk = Class.forName(name, false, loader);
        if (sdk.getClassLoader() != loader) throw new IOException("SDK initializer is not owned by the prepared guest loader");
        Method method = sdk.getMethod("initialize", Context.class);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class)
            throw new IOException("Unexpected SDK initialize(Context) signature");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            method.invoke(null, application); // Same actual host Application that Android attaches to the Activity.
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
}
