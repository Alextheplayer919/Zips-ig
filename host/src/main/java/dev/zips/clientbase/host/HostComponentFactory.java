package dev.zips.clientbase.host;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.content.Intent;

/** Fixed private components only. Does NOT expose an arbitrary class/component loader. */
public final class HostComponentFactory extends AppComponentFactory {
    @Override public android.app.Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (!FirebaseStartup.DISCOVERY.equals(name)) return super.instantiateService(loader, name, intent);
        try {
            if (intent == null || intent.getComponent() == null
                    || !name.equals(intent.getComponent().getClassName())
                    || !ClientBaseApplication.current().getPackageName().equals(intent.getComponent().getPackageName()))
                throw new SecurityException("Expected exact private Firebase discovery component");
            return GameActivityRuntime.createFirebaseDiscoveryService(ClientBaseApplication.current());
        } catch (Exception | LinkageError error) {
            InstantiationException blocked = new InstantiationException("Firebase service creation blocked: " + error);
            blocked.initCause(error); throw blocked;
        }
    }
    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        boolean game = GameActivityRuntime.WRAPPER.equals(name);
        boolean support = GameSupportActivities.isSupported(name);
        if (!game && !support && !HostedGuestPlaceholderActivity.class.getName().equals(name)) {
            return super.instantiateActivity(loader, name, intent);
        }
        try {
            if (intent == null || intent.getComponent() == null
                    || !name.equals(intent.getComponent().getClassName())
                    || !ClientBaseApplication.current().getPackageName().equals(intent.getComponent().getPackageName())) {
                throw new SecurityException("Expected exact private host component");
            }
            if (game) {
                Activity created = GameActivityRuntime.create(ClientBaseApplication.current());
                if (created instanceof AbortedSessionActivity) intent.replaceExtras((android.os.Bundle) null);
                return created;
            }
            if (support) {
                Activity created = GameActivityRuntime.createSupportActivity(ClientBaseApplication.current(), name);
                if (created instanceof AbortedSessionActivity) intent.replaceExtras((android.os.Bundle) null);
                return created;
            }
            return FrameworkActivityRuntime.create(ClientBaseApplication.current());
        } catch (Exception | LinkageError error) {
            InstantiationException blocked = new InstantiationException("Guest Activity creation blocked: " + error);
            blocked.initCause(error);
            throw blocked;
        }
    }
}
