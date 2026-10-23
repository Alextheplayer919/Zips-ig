package dev.zips.clientbase.host;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.content.Intent;

/** Fixed cooperative Activity only. Does NOT expose an arbitrary class/component loader. */
public final class HostComponentFactory extends AppComponentFactory {
    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        boolean game = GameActivityRuntime.WRAPPER.equals(name);
        if (!game && !HostedGuestPlaceholderActivity.class.getName().equals(name)) {
            return super.instantiateActivity(loader, name, intent);
        }
        try {
            if (intent == null || intent.getComponent() == null
                    || !name.equals(intent.getComponent().getClassName())
                    || !ClientBaseApplication.current().getPackageName().equals(intent.getComponent().getPackageName())) {
                throw new SecurityException("Expected exact private host component");
            }
            return game ? GameActivityRuntime.create(ClientBaseApplication.current())
                    : FrameworkActivityRuntime.create(ClientBaseApplication.current());
        } catch (Exception | LinkageError error) {
            InstantiationException blocked = new InstantiationException("Guest Activity creation blocked: " + error);
            blocked.initCause(error);
            throw blocked;
        }
    }
}
