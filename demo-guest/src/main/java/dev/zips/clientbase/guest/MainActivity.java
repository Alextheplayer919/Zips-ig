package dev.zips.clientbase.guest;

import android.app.Activity;
import android.os.Bundle;

/** Standalone comparison: here native UID belongs to the guest installation. */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new DemoGuestEntry().createView(this, getResources()));
    }
}
