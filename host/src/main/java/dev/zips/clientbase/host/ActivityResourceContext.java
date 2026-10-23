package dev.zips.clientbase.host;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

/** Activity-local guest resources, with real host identity, services and storage. */
public final class ActivityResourceContext extends ContextThemeWrapper {
    private final Context resourceContext;
    private final ClassLoader guestLoader;
    private final int themeId;
    private LayoutInflater inflater;

    public ActivityResourceContext(Context base, Context resourceContext, ClassLoader guestLoader, int themeId) {
        super(base, themeId);
        this.resourceContext = resourceContext;
        this.guestLoader = guestLoader;
        this.themeId = themeId;
    }

    @Override public Resources getResources() { return resourceContext.getResources(); }
    @Override public AssetManager getAssets() { return getResources().getAssets(); }
    @Override public ClassLoader getClassLoader() { return guestLoader; }

    @Override public Object getSystemService(String name) {
        if (!Context.LAYOUT_INFLATER_SERVICE.equals(name)) return super.getSystemService(name);
        if (inflater == null) inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
        return inflater;
    }

    @Override public Context createConfigurationContext(Configuration override) {
        return new ActivityResourceContext(getBaseContext().createConfigurationContext(override),
                resourceContext.createConfigurationContext(override), guestLoader, themeId);
    }
}
