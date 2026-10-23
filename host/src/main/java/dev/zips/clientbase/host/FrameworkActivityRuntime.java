package dev.zips.clientbase.host;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import dev.zips.clientbase.contract.ActivityEnvironment;
import dev.zips.clientbase.contract.HostedActivity;
import java.util.ArrayList;
import java.util.List;

/** Developer fixture for the framework seam, separate from Minecraft's execution policy. */
public final class FrameworkActivityRuntime {
    static final String FIXTURE = "dev.zips.clientbase.guest.FrameworkGuestActivity";
    private static final List<String> EVENTS = new ArrayList<>();

    private FrameworkActivityRuntime() {}

    public static Intent fixtureIntent(Context context) {
        return new Intent(context, HostedGuestPlaceholderActivity.class);
    }

    public static synchronized List<String> events() { return new ArrayList<>(EVENTS); }
    public static synchronized void clearEvents() { EVENTS.clear(); }
    private static synchronized void record(String event) {
        if (EVENTS.size() < 128) EVENTS.add(event);
    }

    static Activity create(Context context) throws Exception {
        // Reuse the existing signature/protocol-gated loader: a second loader must
        // not acquire the demo's already-owned JNI library after recreation.
        GuestRuntime.LoadedGuest guest = GuestRuntime.load(context);
        Context resources = context.createPackageContext(GuestRuntime.GUEST_PACKAGE, 0); // resources only, no INCLUDE_CODE
        ActivityInfo declaration = context.getPackageManager().getActivityInfo(
                new ComponentName(GuestRuntime.GUEST_PACKAGE, FIXTURE), PackageManager.GET_META_DATA);
        int theme = declaration.getThemeResource();
        if (theme == 0) throw new IllegalStateException("Guest Activity theme missing");
        Class<?> type = Class.forName(FIXTURE, false, guest.loader);
        if (type.getClassLoader() != guest.loader || !Activity.class.isAssignableFrom(type)
                || !HostedActivity.class.isAssignableFrom(type)) {
            throw new SecurityException("Unexpected guest Activity class or contract identity");
        }
        Activity activity = (Activity) type.getDeclaredConstructor().newInstance();
        ((HostedActivity) activity).configureHost(new ActivityEnvironment() {
            @Override public Context attach(Context base) {
                return new ActivityResourceContext(base, resources, guest.loader, theme);
            }
            @Override public int mapTheme(int requested) {
                // Framework applies the host component's safe placeholder theme
                // after attachment. Map only that sentinel; retain later guest changes.
                return requested == android.R.style.Theme_Material_NoActionBar ? theme : requested;
            }
            @Override public void event(Activity instance, String event) {
                if (instance != activity) throw new IllegalStateException("Wrong Activity instance");
                record(event);
            }
        });
        record("factoryCreated");
        // ActivityThread will attach a real token/window/context and call lifecycle.
        // Never call onCreate, Activity.attach, Instrumentation internals, or native entrypoints here.
        return activity;
    }
}
