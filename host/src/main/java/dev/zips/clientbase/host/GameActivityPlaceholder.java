package dev.zips.clientbase.host;

/** Fail closed if the configured factory/prepared game session is unavailable. */
public final class GameActivityPlaceholder extends android.app.Activity {
    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        throw new IllegalStateException("Game Activity factory/session unavailable; no automatic restart");
    }
}
