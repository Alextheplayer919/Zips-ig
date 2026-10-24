package dev.zips.clientbase.host;

/** Bounds in the overlay window's inset-adjusted display area. */
final class OverlayGeometry {
    static int clamp(int requested,int size,int available){return Math.max(0,Math.min(requested,Math.max(0,available-size)));}
    static int size(int requested,int available){return Math.max(1,Math.min(requested,Math.max(1,available)));}
}
