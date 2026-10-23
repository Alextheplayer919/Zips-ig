package dev.zips.clientbase.host;

import dalvik.system.DexClassLoader;
import java.util.function.BiConsumer;

/** One owner for real guest classes, our bridge and their JVM native libraries. */
final class BootstrapClassLoader extends DexClassLoader {
    private static final String BRIDGE = "dev.zips.clientbase.host.EngineNativeBridge";
    private final BiConsumer<String, String> lookup;

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
        if (!BRIDGE.equals(name) && !"dev.zips.clientbase.host.MinecraftHostedActivity".equals(name) && !GuestNamespaces.owns(name)) return super.loadClass(name, resolve);
        Class<?> found = findLoadedClass(name);
        if (found == null) found = findClass(name);
        if (resolve) resolveClass(found);
        return found;
    }
}
