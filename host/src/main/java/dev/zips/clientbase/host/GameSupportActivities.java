package dev.zips.clientbase.host;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Fixed non-exported :game_host support Activities used by original XAL/Play Games flows
 * (such as Microsoft account sign-in via in-process WebKitWebViewController).
 */
final class GameSupportActivities {
    static final String XAL_BROWSER_LAUNCH = "com.microsoft.xal.browser.BrowserLaunchActivity";
    static final String XAL_WEBVIEW = "com.microsoft.xal.browser.WebKitWebViewController";
    static final String PLAY_GAMES_RESOLUTION = "com.google.android.gms.games.internal.v2.resolution.GamesResolutionActivity";
    static final String GOOGLE_API_ACTIVITY = "com.google.android.gms.common.api.GoogleApiActivity";

    static final Set<String> SUPPORTED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            XAL_BROWSER_LAUNCH,
            XAL_WEBVIEW,
            PLAY_GAMES_RESOLUTION,
            GOOGLE_API_ACTIVITY)));

    private static volatile ClassLoader registeredLoader;

    private GameSupportActivities() {}

    static boolean isSupported(String className) {
        return SUPPORTED.contains(className);
    }

    static synchronized void installLifecycleSupport(Application application, ClassLoader guestLoader) {
        if (guestLoader == null || registeredLoader == guestLoader) return;
        registeredLoader = guestLoader;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
                if (activity.getClass().getClassLoader() == guestLoader) {
                    prepareActivityState(activity.getIntent(), savedInstanceState, guestLoader);
                }
            }

            @Override public void onActivityPostCreated(Activity activity, Bundle savedInstanceState) {
                if (activity.getClass().getClassLoader() == guestLoader
                        && XAL_WEBVIEW.equals(activity.getClass().getName())) {
                    enableWebViewDomStorage(activity.findViewById(android.R.id.content));
                }
            }

            @Override public void onActivityPreResumed(Activity activity) {
                if (activity.getClass().getClassLoader() == guestLoader) {
                    prepareActivityState(activity.getIntent(), null, guestLoader);
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    static void prepareActivityState(Intent intent, Bundle savedInstanceState, ClassLoader guestLoader) {
        if (guestLoader == null) return;
        if (intent != null) intent.setExtrasClassLoader(guestLoader);
        if (savedInstanceState != null) savedInstanceState.setClassLoader(guestLoader);
    }

    static int enableWebViewDomStorage(View root) {
        if (root == null) return 0;
        if (root instanceof WebView) {
            ((WebView) root).getSettings().setDomStorageEnabled(true);
            return 1;
        }
        int count = 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0, n = group.getChildCount(); i < n; i++) {
                count += enableWebViewDomStorage(group.getChildAt(i));
            }
        }
        return count;
    }
}
