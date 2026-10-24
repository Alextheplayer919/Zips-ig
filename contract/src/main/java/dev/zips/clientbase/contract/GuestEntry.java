package dev.zips.clientbase.contract;

import android.app.Activity;
import android.content.res.Resources;
import android.view.View;

/** Cooperative demo protocol, not a Minecraft or arbitrary-Activity API. */
public interface GuestEntry {
    int API_VERSION = 1;
    View createView(Activity host, Resources guestResources);
    int processId();
    int userId();
}
