package dev.zips.clientbase.host;

import dalvik.system.DexClassLoader;
import java.util.function.BiConsumer;

/** One owner for real guest classes, our bridge and their JVM native libraries. */
final class BootstrapClassLoader extends DexClassLoader {
    private static final String BRIDGE = "dev.zips.clientbase.host.EngineNativeBridge";
    private final BiConsumer<String, String> lookup;
    static boolean ownsHostClass(String name) {
        return family(name, BRIDGE) || family(name, "dev.zips.clientbase.host.MinecraftHostedActivity")
                || family(name, "dev.zips.clientbase.host.HostedSurfaceObserver");
    }
    private static boolean family(String name, String root) {
        return root.equals(name) || name.startsWith(root + "$");
    }

    BootstrapClassLoader(String dex, String optimized, String nativePaths, ClassLoader parent,
            BiConsumer<String, String> lookup) {
        super(dex, optimized, nativePaths, parent);
        this.lookup = lookup;
    }

    // Resolve a candidate for hash verification, without loading a native library.
    String resolveNativeLibrary(String name) { return super.findLibrary(name); }

    @Override public String findLibrary(String name) {
        String path = super.findLibrary(name);
        lookup.accept(name, path);
        return path;
    }

    @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        if (!ownsHostClass(name) && !GuestNamespaces.owns(name)) return super.loadClass(name, resolve);
        Class<?> found = findLoadedClass(name);
        if (found == null) found = findClass(name);
        if (resolve) resolveClass(found);
        return found;
    }
}
