package dev.zips.clientbase.host;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Static, bounded DEX inspection only. Never define or initialize a guest class. */
public final class DexBootstrapInspector {
    private static final int MAX_DEX = 128 * 1024 * 1024;
    private static final int MAX_UNITS = 256;
    private static final List<String> LIBRARIES = Arrays.asList("minecraftpe", "fmod", "maesdk",
            "PlayFabMultiplayer", "HttpClient.Android", "c++_shared", "clientbase_demo");
    private final ByteBuffer data;
    private final int strings, types, protos, fields, methods, classes;
    private final int stringCount, typeCount, protoCount, fieldCount, methodCount, classCount;

    DexBootstrapInspector(ByteBuffer bytes) throws IOException {
        data = bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        if (data.limit() < 112 || data.limit() > MAX_DEX) throw new IOException("Invalid DEX length");
        byte[] magic = new byte[8];
        data.get(magic);
        String version = new String(magic, 4, 3, StandardCharsets.US_ASCII);
        if (magic[0] != 'd' || magic[1] != 'e' || magic[2] != 'x' || magic[3] != '\n'
                || magic[7] != 0 || !Arrays.asList("035", "037", "038", "039", "040").contains(version)) {
            throw new IOException("Unsupported DEX magic/version; no runtime fallback");
        }
        if (u32(32) != data.limit() || u32(36) != 112 || u32(40) != 0x12345678) {
            throw new IOException("Unsupported DEX header or byte order");
        }
        stringCount = u32(56); strings = table(60, stringCount, 4);
        typeCount = u32(64); types = table(68, typeCount, 4);
        protoCount = u32(72); protos = table(76, protoCount, 12);
        fieldCount = u32(80); fields = table(84, fieldCount, 8);
        methodCount = u32(88); methods = table(92, methodCount, 8);
        classCount = u32(96); classes = table(100, classCount, 32);
    }

    private int table(int header, int count, int stride) throws IOException {
        if (count > 2000000) throw new IOException("Oversized DEX table");
        int offset = u32(header);
        bounds(offset, (long) count * stride);
        return offset;
    }

    private void bounds(int offset, long bytes) throws IOException {
        if (offset < 0 || bytes < 0 || (long) offset + bytes > data.limit()) throw new IOException("DEX offset out of bounds");
    }
    private int u16(int offset) throws IOException { bounds(offset, 2); return data.getShort(offset) & 65535; }
    private int u32(int offset) throws IOException {
        bounds(offset, 4);
        int value = data.getInt(offset);
        if (value < 0) throw new IOException("DEX value exceeds bounded signed range");
        return value;
    }
    private int uleb(int[] cursor) throws IOException {
        long value = 0;
        for (int i = 0; i < 5; i++) {
            bounds(cursor[0], 1);
            int b = data.get(cursor[0]++) & 255;
            value |= (long) (b & 127) << (7 * i);
            if ((b & 128) == 0) {
                if (value > Integer.MAX_VALUE) break;
                return (int) value;
            }
        }
        throw new IOException("Invalid/oversized DEX ULEB128");
    }
    private String string(int index) throws IOException {
        if (index < 0 || index >= stringCount) throw new IOException("Invalid string index");
        int[] cursor = {u32(strings + index * 4)};
        uleb(cursor); // UTF-16 length; the selected names should be ordinary ASCII.
        StringBuilder result = new StringBuilder();
        while (result.length() <= 2048) {
            bounds(cursor[0], 1);
            int b = data.get(cursor[0]++) & 255;
            if (b == 0) return result.toString();
            if (b > 127) throw new IOException("Non-ASCII selected DEX string; no lossy decoding");
            result.append((char) b);
        }
        throw new IOException("Selected DEX string too long");
    }
    private String type(int index) throws IOException {
        if (index < 0 || index >= typeCount) throw new IOException("Invalid type index");
        return string(u32(types + index * 4));
    }
    private String method(int index) throws IOException {
        if (index < 0 || index >= methodCount) throw new IOException("Invalid method index");
        int at = methods + index * 8;
        int proto = u16(at + 2);
        if (proto >= protoCount) throw new IOException("Invalid proto index");
        int p = protos + proto * 12;
        int parameters = u32(p + 8);
        StringBuilder signature = new StringBuilder("(");
        if (parameters != 0) {
            int count = u32(parameters);
            if (count > 256) throw new IOException("Too many method parameters");
            bounds(parameters + 4, (long) count * 2);
            for (int i = 0; i < count; i++) signature.append(type(u16(parameters + 4 + i * 2)));
        }
        String result = type(u16(at)) + "->" + string(u32(at + 4)) + signature + ")" + type(u32(p + 4));
        if (result.length() > 512) throw new IOException("Selected method signature too long");
        return result;
    }
    private String field(int index) throws IOException {
        if (index < 0 || index >= fieldCount) throw new IOException("Invalid field index");
        int at = fields + index * 8;
        String result = type(u16(at)) + "->" + string(u32(at + 4)) + ":" + type(u16(at + 2));
        if (result.length() > 512) throw new IOException("Selected field signature too long");
        return result;
    }

    JSONObject inspect(String descriptor) throws IOException, JSONException {
        for (int i = 0; i < classCount; i++) {
            int at = classes + i * 32;
            if (!descriptor.equals(type(u32(at)))) continue;
            JSONObject result = new JSONObject();
            result.put("class", descriptor);
            int parent = data.getInt(at + 8); // NO_INDEX is the unsigned sentinel 0xffffffff.
            result.put("superclass", parent == -1 ? JSONObject.NULL : type(parent));
            JSONArray nativeMethods = new JSONArray();
            result.put("native_methods", nativeMethods);
            result.put("clinit", JSONObject.NULL);
            int encoded = u32(at + 24);
            int nativeCount = 0;
            if (encoded != 0) {
                int[] cursor = {encoded};
                int staticFields = uleb(cursor), instanceFields = uleb(cursor);
                int direct = uleb(cursor), virtual = uleb(cursor);
                if ((long) staticFields + instanceFields > 131072 || (long) direct + virtual > 131072) {
                    throw new IOException("Oversized class data");
                }
                for (int j = 0; j < staticFields + instanceFields; j++) { uleb(cursor); uleb(cursor); }
                for (int count : new int[] {direct, virtual}) {
                    int index = 0;
                    for (int j = 0; j < count; j++) {
                        index = Math.addExact(index, uleb(cursor));
                        int flags = uleb(cursor), code = uleb(cursor);
                        String name = method(index);
                        if ((flags & 0x100) != 0) {
                            if (nativeCount++ < 64) nativeMethods.put(name);
                        }
                        if (name.contains("-><clinit>()V")) {
                            if (code == 0) throw new IOException("Static initializer has no code");
                            result.put("clinit", code(code));
                        }
                    }
                }
            }
            result.put("native_method_count", nativeCount);
            result.put("native_methods_truncated", nativeCount > 64);
            return result;
        }
        return null;
    }

    private JSONObject code(int at) throws IOException, JSONException {
        bounds(at, 16);
        int total = u32(at + 12), count = Math.min(total, MAX_UNITS);
        bounds(at + 16, (long) total * 2);
        int[] units = new int[count];
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < count; i++) {
            units[i] = u16(at + 16 + i * 2);
            hex.append(String.format(java.util.Locale.ROOT, "%04x ", units[i]));
        }
        JSONObject result = new JSONObject();
        result.put("registers", u16(at)); result.put("code_units_total", total);
        result.put("code_units_hex", hex.toString().trim());
        result.put("truncated", count != total);
        JSONArray refs = new JSONArray(); result.put("references", refs);
        int cursor = 0;
        boolean complete = count == total;
        while (cursor < count) {
            int op = units[cursor] & 255;
            int width = width(units, cursor);
            if (width <= 0 || cursor + width > count) {
                result.put("scan_stop_unit", cursor); result.put("reference_scan_complete", false);
                return result;
            }
            // Invoke-custom call sites and method-handle/type pools need a separate
            // resolver. Preserve raw bytes but never label these references complete.
            if (op >= 0xfc) complete = false;
            String ref = null;
            if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78) || op == 0xfa || op == 0xfb) {
                ref = method(units[cursor + 1]);
            } else if (op == 0x1a || op == 0x1b) {
                int index = units[cursor + 1];
                if (op == 0x1b) index |= units[cursor + 2] << 16;
                String literal = string(index);
                ref = "const-string: " + (LIBRARIES.contains(literal) ? literal : "[non-library string omitted]");
            } else if (op >= 0x52 && op <= 0x6d) ref = field(units[cursor + 1]);
            else if (op == 0x1c || op == 0x1f || op == 0x20 || (op >= 0x22 && op <= 0x25)) ref = type(units[cursor + 1]);
            if (ref != null && refs.length() < 64) {
                JSONObject item = new JSONObject(); item.put("unit", cursor); item.put("reference", ref); refs.put(item);
            }
            cursor += width;
        }
        result.put("reference_scan_complete", complete && refs.length() < 64);
        return result;
    }

    static int width(int[] u, int at) {
        int op = u[at] & 255;
        if (op == 0) {
            if (u[at] == 0) return 1;
            if (at + 1 >= u.length) return -1;
            if (u[at] == 0x100) return 4 + 2 * u[at + 1];
            if (u[at] == 0x200) return 2 + 4 * u[at + 1];
            if (u[at] == 0x300 && at + 3 < u.length) {
                long size = (long) u[at + 2] | ((long) u[at + 3] << 16);
                long width = 4 + ((long) u[at + 1] * size + 1) / 2;
                return width > Integer.MAX_VALUE ? -1 : (int) width;
            }
            return -1;
        }
        if (op <= 0x11) return op == 3 || op == 6 || op == 9 ? 3 : op == 2 || op == 5 || op == 8 ? 2 : 1;
        if (op == 0x18) return 5;
        if (op == 0x14 || op == 0x17 || op == 0x1b || (op >= 0x24 && op <= 0x26) || (op >= 0x2a && op <= 0x2c)) return 3;
        if (op == 0x12 || op == 0x1d || op == 0x1e || op == 0x21 || op == 0x27 || op == 0x28 || (op >= 0x7b && op <= 0x8f) || (op >= 0xb0 && op <= 0xcf)) return 1;
        if ((op >= 0x13 && op <= 0x23) || op == 0x29 || (op >= 0x2d && op <= 0x3d) || (op >= 0x44 && op <= 0x6d) || (op >= 0x90 && op <= 0xaf) || (op >= 0xd0 && op <= 0xe2) || op >= 0xfe) return 2;
        if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78) || op == 0xfc || op == 0xfd) return 3;
        if (op == 0xfa || op == 0xfb) return 4;
        return -1;
    }

    public static JSONObject inspectApks(List<String> apks, String descriptor, File cache) throws IOException, JSONException {
        File work = new File(cache, "bootstrap-inspection");
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("Cannot create bootstrap scratch directory");
        File[] stale = work.listFiles((dir, name) -> name.startsWith("bootstrap-") && name.endsWith(".dex.tmp"));
        if (stale == null) throw new IOException("Cannot list bootstrap scratch directory");
        // A forced process kill can bypass finally. Clean only our stale scratch files.
        for (File file : stale) java.nio.file.Files.deleteIfExists(file.toPath());
        int entries = 0;
        long totalBytes = 0;
        for (int apkIndex = 0; apkIndex < apks.size(); apkIndex++) {
            try (ZipFile zip = new ZipFile(apks.get(apkIndex))) {
                java.util.Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry entry = all.nextElement();
                    if (!entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) continue;
                    if (++entries > 32 || entry.getSize() < 112 || entry.getSize() > MAX_DEX) throw new IOException("DEX inventory limit exceeded");
                    File temporary = File.createTempFile("bootstrap-", ".dex.tmp", work);
                    try {
                        try (InputStream in = zip.getInputStream(entry); FileOutputStream out = new FileOutputStream(temporary)) {
                            byte[] b = new byte[65536]; long copied = 0;
                            for (int n; (n = in.read(b)) != -1;) {
                                copied += n;
                                totalBytes += n;
                                if (totalBytes > 256L * 1024 * 1024) throw new IOException("Total DEX scan exceeds 256 MiB");
                                if (copied > MAX_DEX) throw new IOException("DEX stream exceeds limit");
                                out.write(b, 0, n);
                            }
                            if (copied != entry.getSize()) throw new IOException("DEX size mismatch");
                        }
                        try (RandomAccessFile file = new RandomAccessFile(temporary, "r")) {
                            DexBootstrapInspector reader = new DexBootstrapInspector(file.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, file.length()));
                            JSONObject result = reader.inspect(descriptor);
                            if (result != null) {
                                result.put("apk_index", apkIndex); result.put("dex_entry", entry.getName());
                                result.put("dex_bytes", temporary.length()); result.put("dex_crc", entry.getCrc());
                                return result;
                            }
                        }
                    } finally { java.nio.file.Files.deleteIfExists(temporary.toPath()); }
                }
            }
        }
        throw new IOException("Target class not found within bounded DEX inventory");
    }
}
