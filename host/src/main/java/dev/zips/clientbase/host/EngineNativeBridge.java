package dev.zips.clientbase.host;

/** Loaded in the probe's own class-loader namespace, never by the UI. */
public final class EngineNativeBridge {
    static { System.loadLibrary("clientbase_engineprobe"); }
    private EngineNativeBridge() {}
    private static native String nativeProbe(String path);
    public static String run(String path) { return nativeProbe(java.util.Objects.requireNonNull(path)); }
}
