package dev.zips.clientbase.host;

import static org.junit.Assert.*;
import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercise the denied OS operation with our own context, never Minecraft code. */
@RunWith(AndroidJUnit4.class)
public final class BootstrapNetworkPermissionTest {
    @Test public void networkStatePermissionIsNormalAndGrantedToHost() throws Exception {
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PermissionInfo permission = host.getPackageManager().getPermissionInfo(Manifest.permission.ACCESS_NETWORK_STATE, 0);
        assertEquals(PermissionInfo.PROTECTION_NORMAL, permission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
        assertEquals(PackageManager.PERMISSION_GRANTED, host.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE));
        MinecraftBootstrap.verifyHostPermissions(host);
    }

    @Test public void missingPermissionStopsPreflightBeforeAnyGuestInitialization() throws Exception {
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context denied = new ContextWrapper(host) {
            @Override public int checkSelfPermission(String permission) {
                return Manifest.permission.ACCESS_NETWORK_STATE.equals(permission)
                        ? PackageManager.PERMISSION_DENIED : super.checkSelfPermission(permission);
            }
        };
        try { MinecraftBootstrap.verifyHostPermissions(denied); fail("Denied host permission accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("android.permission.ACCESS_NETWORK_STATE")); }
    }

    @Test public void hostCanRegisterAndRemoveNetworkObserverWithoutPromptOrTraffic() {
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ConnectivityManager connectivity = host.getSystemService(ConnectivityManager.class);
        assertNotNull(connectivity);
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback();
        // Same public overload seen in the phone's NetworkObserver.Initialize stack.
        // No callback/network arrival is required; this tests permission enforcement.
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().build(), callback);
        try { connectivity.getActiveNetwork(); }
        finally { connectivity.unregisterNetworkCallback(callback); }
    }
}
