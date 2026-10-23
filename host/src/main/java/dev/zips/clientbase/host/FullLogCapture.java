package dev.zips.clientbase.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Stream every available byte to disk; retain only a small tail for the UI. */
final class FullLogCapture {
    static final int PREVIEW_BYTES = 24 * 1024;
    static String copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192], tail = new byte[PREVIEW_BYTES];
        int next = 0, size = 0;
        long total = 0;
        for (int n; (n = input.read(buffer)) != -1;) {
            output.write(buffer, 0, n);
            total += n;
            int first = Math.min(n, tail.length - next);
            System.arraycopy(buffer, 0, tail, next, first);
            if (n > first) System.arraycopy(buffer, first, tail, 0, n - first);
            next = (next + n) % tail.length;
            size = (int) Math.min(total, tail.length);
        }
        byte[] ordered = new byte[size];
        int start = total >= tail.length ? next : 0;
        for (int i = 0; i < size; i++) ordered[i] = tail[(start + i) % tail.length];
        return (total > size ? "[UI preview: last " + size + " of " + total + " bytes; full available log saved for TXT export]\n" : "")
                + new String(ordered, StandardCharsets.UTF_8);
    }
}
