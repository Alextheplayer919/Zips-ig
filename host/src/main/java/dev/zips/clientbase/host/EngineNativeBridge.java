package dev.zips.clientbase.host;

/** Loaded in the probe's own class-loader namespace, never by the UI. */
public final class EngineNativeBridge {
    static { System.loadLibrary("clientbase_engineprobe"); }
    private EngineNativeBridge() {}
    private static native String nativeProbe(String path);
    private static native String nativeDescribeLoaded(String path);
    private static native int nativeInitializationCount();
    private static native String nativeMemorySnapshot(String path);
    private static native String nativeModuleControl(
            String path, int op, boolean flyDesired, boolean auraDesired,
            boolean attackMobs, boolean multiTarget, boolean checkHurtTime);
    public static String moduleControl(
            String path, int op, boolean flyDesired, boolean auraDesired,
            boolean attackMobs, boolean multiTarget, boolean checkHurtTime) {
        return nativeModuleControl(
                java.util.Objects.requireNonNull(path), op, flyDesired, auraDesired,
                attackMobs, multiTarget, checkHurtTime);
    }
    public static String memorySnapshot(String path) { return nativeMemorySnapshot(java.util.Objects.requireNonNull(path)); }
    public static int initializationCount() { return nativeInitializationCount(); }
    public static String describeLoaded(String path) { return nativeDescribeLoaded(java.util.Objects.requireNonNull(path)); }
    public static String run(String path) { return nativeProbe(java.util.Objects.requireNonNull(path)); }
}
