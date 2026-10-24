package dev.zips.clientbase.host;

import android.app.Activity;
import android.os.Bundle;

/**
 * Fail-closed Activity returned inside :game_host only when Android attempts to recreate
 * a hosted Activity after a prior process exit without a fresh prepared session.
 * Finishing cleanly prevents a second uncaught InstantiationException crash that would
 * cause Android AppErrors to quarantine :game_host in mBadProcesses ("Game service binding rejected").
 */
public final class AbortedSessionActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        // Ignore savedInstanceState so unverified/guest parcels are never unmarshalled without a session.
        super.onCreate(null);
        setResult(RESULT_CANCELED);
        finish();
        overridePendingTransition(0, 0);
    }
}
