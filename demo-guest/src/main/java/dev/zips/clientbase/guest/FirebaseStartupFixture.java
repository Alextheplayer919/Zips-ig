package dev.zips.clientbase.guest;

import android.app.Application;
import android.content.Context;

/** Our own public-initializer contract fixture, not a Firebase implementation. */
public final class FirebaseStartupFixture {
    private static final FirebaseStartupFixture INSTANCE = new FirebaseStartupFixture();
    private static String mode = "ok";
    private static Application application;
    private static ClassLoader loader;
    public static void mode(String value) { mode = value; }
    public static FirebaseStartupFixture initializeApp(Context context) {
        application = (Application) context; loader = Thread.currentThread().getContextClassLoader();
        if ("throw".equals(mode)) throw new IllegalStateException("fixture initialization error");
        return "null".equals(mode) ? null : INSTANCE;
    }
    public static FirebaseStartupFixture getInstance() {
        return "mismatch".equals(mode) ? new FirebaseStartupFixture() : INSTANCE;
    }
    public static Application application() { return application; }
    public static ClassLoader loader() { return loader; }
    public static final class Messaging {
        private static final Messaging INSTANCE = new Messaging();
        public static Messaging getInstance() { return "missingMessaging".equals(mode) ? null : INSTANCE; }
    }
}
