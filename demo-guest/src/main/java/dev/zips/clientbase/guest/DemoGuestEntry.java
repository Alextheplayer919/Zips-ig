package dev.zips.clientbase.guest;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import dev.zips.clientbase.contract.GuestEntry;

public final class DemoGuestEntry implements GuestEntry {
    static { System.loadLibrary("clientbase_demo"); }
    private static native int nativeProcessId();
    private static native int nativeUserId();

    @Override public int processId() { return nativeProcessId(); }
    @Override public int userId() { return nativeUserId(); }

    @Override public View createView(Activity host, Resources resources) {
        TextView view = new TextView(host);
        view.setText(resources.getString(R.string.guest_banner)
                + "\n\nNative PID: " + processId() + "\nNative UID: " + userId());
        view.setTextSize(18);
        view.setTextColor(Color.rgb(113, 217, 193));
        view.setPadding(24, 24, 24, 24);
        return view;
    }
}
