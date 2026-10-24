package dev.zips.clientbase.guest;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

/** Our own initializer test fixture; not a substitute Google/Minecraft implementation. */
public final class StartupFixture {
    private static Application application;
    private static ClassLoader loader;
    public static void initialize(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Wrong thread");
        application = (Application) context;
        loader = Thread.currentThread().getContextClassLoader();
    }
    public static Application application() { return application; }
    public static ClassLoader loader() { return loader; }
    public static final class Failing {
        public static void initialize(Context context) { throw new IllegalStateException("fixture SDK startup failed"); }
    }
    public static final class WrongSignature {
        public static int initialize(Context context) { return 1; }
    }
}
