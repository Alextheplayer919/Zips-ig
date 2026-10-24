package dev.zips.clientbase.contract;

import android.app.Activity;
import android.content.Context;

/** Host-owned environment supplied before framework Activity attachment. */
public interface ActivityEnvironment {
    Context attach(Context frameworkBase);
    int mapTheme(int requestedTheme);
    void event(Activity activity, String event);
}
