package dev.zips.clientbase.host;

import android.app.Activity;
import android.os.Bundle;

/** Host component declaration. A missing factory must fail, never masquerade as a guest. */
public final class HostedGuestPlaceholderActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        throw new IllegalStateException("Guest Activity was not created by the host component factory");
    }
}
