package dev.zips.clientbase.host;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

/** Original public Firebase APIs; default options come from the installed game's resource view. */
final class FirebaseStartup {
    static final String DISCOVERY = "com.google.firebase.components.ComponentDiscoveryService";
    private static final String APP = "com.google.firebase.FirebaseApp";
    private static final String MESSAGING = "com.google.firebase.messaging.FirebaseMessaging";
    static void initialize(Application application, ClassLoader loader, Consumer<String> report) throws Exception {
        GameActivityRuntime.requireGameProcess(application);
        report.accept("FIREBASE_INITIALIZATION_ENTERED (original public API; not token delivery)");
        try {
            invoke(application, loader, APP, MESSAGING);
            report.accept("FIREBASE_DEFAULT_APP_AND_MESSAGING_READY (not token or push delivery success)");
        } catch (Exception | LinkageError error) {
            report.accept("FIREBASE_INITIALIZATION_BLOCKED\n" + MinecraftBootstrap.describeFailure(error));
            throw error;
        }
    }
    // Test seam uses explicitly named original demo fixtures, never fake Firebase/Minecraft classes.
    static void invoke(Application application, ClassLoader loader, String appName, String messagingName) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IOException("Firebase startup requires the main thread");
        Class<?> app = Class.forName(appName, false, loader), messaging = Class.forName(messagingName, false, loader);
        if (app.getClassLoader() != loader || messaging.getClassLoader() != loader)
            throw new IOException("Firebase classes are not owned by the prepared guest loader");
        Method initialize = app.getMethod("initializeApp", Context.class);
        Method getDefault = app.getMethod("getInstance"), getMessaging = messaging.getMethod("getInstance");
        requireSignature(initialize, app); requireSignature(getDefault, app); requireSignature(getMessaging, messaging);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Object initialized = initialize.invoke(null, application);
            if (initialized == null) throw new IOException("Firebase default options missing; initializeApp returned null");
            if (initialized != getDefault.invoke(null)) throw new IOException("Firebase default app identity mismatch");
            // Fail here in Java, not later inside Minecraft JNI, if registrar discovery is incomplete.
            if (getMessaging.invoke(null) == null) throw new IOException("Firebase Messaging component unavailable");
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
    private static void requireSignature(Method method, Class<?> result) throws IOException {
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result)
            throw new IOException("Unexpected Firebase public API signature: " + method.getName());
    }
}
