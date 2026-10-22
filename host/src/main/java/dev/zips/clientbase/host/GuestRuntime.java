package dev.zips.clientbase.host;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Process;
import dalvik.system.DexClassLoader;
import dev.zips.clientbase.contract.GuestEntry;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Demo-only cooperative loader. No hidden APIs, guest Application, or activity spoofing. */
public final class GuestRuntime {
    public static final String GUEST_PACKAGE = "dev.zips.clientbase.guest";
    private static final String ENTRY_CLASS = GUEST_PACKAGE + ".DemoGuestEntry";
    private static LoadedGuest cached;

    private GuestRuntime() {}

    public static final class LoadedGuest {
        public final GuestEntry entry;
        public final Resources resources;
        public final int installedUid;
        public final int apkCount;
        public final String dexPath;
        // Keep the loader alive and reuse it across Activity recreation. JNI libraries
        // cannot be loaded into a second class loader in the same process.
        public final DexClassLoader loader;

        private LoadedGuest(GuestEntry entry, Resources resources, ApplicationInfo info,
                            int apkCount, String dexPath, DexClassLoader loader) {
            this.entry = entry;
            this.resources = resources;
            this.installedUid = info.uid;
            this.apkCount = apkCount;
            this.dexPath = dexPath;
            this.loader = loader;
        }
    }

    public static synchronized LoadedGuest load(Context context)
            throws PackageManager.NameNotFoundException, ReflectiveOperationException, IOException {
        if (cached != null) return cached;
        Context app = context.getApplicationContext();
        PackageManager pm = app.getPackageManager();
        ApplicationInfo info = pm.getApplicationInfo(GUEST_PACKAGE, PackageManager.GET_META_DATA);
        if (pm.checkSignatures(app.getPackageName(), GUEST_PACKAGE) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Demo signature mismatch. Install both APKs from the same build.");
        }
        if (info.metaData == null || info.metaData.getInt("dev.zips.clientbase.API_VERSION", -1)
                != GuestEntry.API_VERSION) {
            throw new SecurityException("Unsupported demo protocol version.");
        }
        List<String> paths = new ArrayList<>();
        paths.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) paths.add(split);
        }
        for (String path : paths) {
            if (path == null || !new File(path).isFile() || !new File(path).canRead()) {
                throw new IOException("Installed guest APK is not readable.");
            }
        }
        if (info.nativeLibraryDir == null
                || !new File(info.nativeLibraryDir, "libclientbase_demo.so").isFile()) {
            throw new IOException("Extracted demo native library missing. Reinstall the matching demo APK; "
                    + "this base supports ARM64 and x86_64 only.");
        }
        File optimized = new File(app.getCodeCacheDir(), "demo-dex");
        if (!optimized.isDirectory() && !optimized.mkdirs()) {
            throw new IOException("Cannot create private code cache.");
        }
        String dexPath = String.join(File.pathSeparator, paths);
        // Parent-first loading preserves one GuestEntry interface identity. Both APKs
        // contain the contract so the guest also works when opened independently.
        DexClassLoader loader = new DexClassLoader(dexPath, optimized.getAbsolutePath(),
                info.nativeLibraryDir, app.getClassLoader());
        Object instance = Class.forName(ENTRY_CLASS, true, loader).getDeclaredConstructor().newInstance();
        if (!(instance instanceof GuestEntry)) {
            throw new IllegalStateException("Guest contract/class-loader identity mismatch.");
        }
        GuestEntry entry = (GuestEntry) instance;
        if (entry.processId() != Process.myPid() || entry.userId() != Process.myUid()) {
            throw new IllegalStateException("Native process identity does not match the host.");
        }
        cached = new LoadedGuest(entry, pm.getResourcesForApplication(info), info,
                paths.size(), dexPath, loader);
        return cached;
    }
}
