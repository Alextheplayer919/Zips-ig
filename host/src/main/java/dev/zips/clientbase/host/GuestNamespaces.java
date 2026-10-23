package dev.zips.clientbase.host;

/** Package-private AndroidX/Kotlin calls require one runtime package/loader, not a parent/child mix. */
final class GuestNamespaces {
    private GuestNamespaces() {}
    static boolean owns(String name) {
        return name.startsWith("androidx.") || name.startsWith("kotlin.") || name.startsWith("kotlinx.");
    }
}
