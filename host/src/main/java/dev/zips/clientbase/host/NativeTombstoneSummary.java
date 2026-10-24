package dev.zips.clientbase.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded selected-field reader for Android's tombstone.proto; no memory/register dumps. */
final class NativeTombstoneSummary {
    private NativeTombstoneSummary() {}

    private static final class Reader {
        final byte[] data;
        final int end;
        final int[] budget;
        int pos, field, wire, start, length;
        long value;
        Reader(byte[] data, int start, int end, int[] budget) {
            this.data = data; this.pos = start; this.end = end; this.budget = budget;
        }
        long varint() throws IOException {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                if (pos >= end) throw new IOException("truncated varint");
                int b = data[pos++] & 255;
                if (i == 9 && b > 1) throw new IOException("varint overflow");
                value |= (long) (b & 127) << (i * 7);
                if ((b & 128) == 0) return value;
            }
            throw new IOException("invalid varint");
        }
        boolean next() throws IOException {
            if (pos == end) return false;
            if (--budget[0] < 0) throw new IOException("field budget exhausted");
            long tag = varint();
            if (tag <= 0 || tag > 0xffffffffL || (tag >>> 3) == 0) throw new IOException("invalid field tag");
            field = (int) (tag >>> 3); wire = (int) (tag & 7);
            start = pos; length = 0; value = 0;
            if (wire == 0) { value = varint(); return true; }
            if (wire == 1) length = 8;
            else if (wire == 5) length = 4;
            else if (wire == 2) {
                long size = varint();
                if (size < 0 || size > end - pos) throw new IOException("truncated/oversized field");
                start = pos; length = (int) size;
            } else throw new IOException("unsupported wire type " + wire);
            if (length > end - pos) throw new IOException("truncated fixed field");
            pos += length;
            return true;
        }
        Reader nested() throws IOException {
            if (wire != 2) throw new IOException("unexpected field type");
            return new Reader(data, start, start + length, budget);
        }
        String text() throws IOException {
            if (wire != 2) throw new IOException("unexpected string type");
            int size = Math.min(length, 4096);
            return new String(data, start, size, StandardCharsets.UTF_8)
                    .replace('\0', '?') + (size < length ? " [truncated]" : "");
        }
    }

    static String decode(byte[] bytes, int expectedPid, int expectedUid, boolean inputTruncated) throws IOException {
        if (expectedPid <= 0 || expectedUid < 0) throw new IOException("invalid expected trace identity");
        if (bytes.length > 8 * 1024 * 1024) throw new IOException("trace exceeds parser input limit");
        Reader root = new Reader(bytes, 0, bytes.length, new int[] {100000});
        long pid = -1, uid = -1, tid = -1;
        String abort = "", signal = "", partial = "";
        List<int[]> threads = new ArrayList<>();
        try {
            while (root.next()) {
                if (root.wire == 0 && root.field == 5) pid = root.value;
                else if (root.wire == 0 && root.field == 6) tid = root.value;
                else if (root.wire == 0 && root.field == 7) uid = root.value;
                else if (root.field == 10 && root.wire == 2) signal = signal(root.nested());
                else if (root.field == 14 && root.wire == 2) abort = root.text();
                else if (root.field == 16 && root.wire == 2 && threads.size() < 512) {
                    // Keep only ranges; no copies of thread memory/register fields.
                    threads.add(new int[] {root.start, root.start + root.length});
                }
                // All other fields, especially memory, log buffers, registers and FDs, are omitted.
            }
        } catch (IOException incomplete) { partial = incomplete.getMessage(); }
        if (pid != expectedPid || uid != expectedUid) throw new IOException("trace PID/UID mismatch or unrecognized format; contents withheld");
        StringBuilder out = new StringBuilder("Native tombstone (selected fields only)\n");
        out.append("pid: ").append(pid).append(" uid: ").append(uid).append(" crashing tid: ").append(tid).append('\n');
        if (!signal.isEmpty()) out.append("Signal: ").append(signal).append('\n');
        if (!abort.isEmpty()) out.append("Abort message: ").append(abort).append('\n');
        boolean found = false;
        try {
            for (int[] range : threads) {
                Reader entry = new Reader(bytes, range[0], range[1], root.budget);
                long key = 0; int threadStart = -1, threadEnd = -1;
                while (entry.next()) {
                    if (entry.field == 1 && entry.wire == 0) key = entry.value;
                    else if (entry.field == 2 && entry.wire == 2) { threadStart = entry.start; threadEnd = entry.start + entry.length; }
                }
                if (key == tid && threadStart >= 0) {
                    out.append(thread(new Reader(bytes, threadStart, threadEnd, root.budget)));
                    found = true; break;
                }
            }
        } catch (IOException incomplete) { partial = incomplete.getMessage(); }
        if (!found) out.append("Crashing-thread frames unavailable in parsed prefix.\n");
        if (inputTruncated || !partial.isEmpty()) out.append("Trace summary incomplete: ").append(inputTruncated ? "input cap reached; " : "").append(partial).append('\n');
        return out.toString();
    }

    private static String signal(Reader r) throws IOException {
        long number = 0, code = 0; String name = "", codeName = "";
        while (r.next()) {
            if (r.field == 1 && r.wire == 0) number = r.value;
            else if (r.field == 2 && r.wire == 2) name = r.text();
            else if (r.field == 3 && r.wire == 0) code = r.value;
            else if (r.field == 4 && r.wire == 2) codeName = r.text();
        }
        return name + " (" + number + "), code " + code + " " + codeName;
    }

    private static String thread(Reader r) throws IOException {
        StringBuilder out = new StringBuilder(); int frames = 0;
        while (r.next()) {
            if (r.field == 2 && r.wire == 2) out.append("Thread: ").append(r.text()).append('\n');
            else if (r.field == 4 && r.wire == 2 && frames < 32) {
                out.append('#').append(frames++).append(' ').append(frame(r.nested())).append('\n');
            }
        }
        return out.append("Frames shown: ").append(frames).append(" (maximum 32)\n").toString();
    }

    private static String frame(Reader r) throws IOException {
        long pc = 0, offset = 0; String file = "", function = "", build = "";
        while (r.next()) {
            if (r.field == 1 && r.wire == 0) pc = r.value; // ELF-relative PC, not a stable process address.
            else if (r.field == 4 && r.wire == 2) function = r.text();
            else if (r.field == 5 && r.wire == 0) offset = r.value;
            else if (r.field == 6 && r.wire == 2) file = r.text();
            else if (r.field == 8 && r.wire == 2) build = r.text();
        }
        return "rel_pc=0x" + Long.toHexString(pc) + " " + file + " " + function + "+" + offset + " build_id=" + build;
    }
}
