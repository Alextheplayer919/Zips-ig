package dev.zips.clientbase.host;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZFileOptions;

/** Accept only the exact submitted archive/library. Verify archive before parsing. */
public final class EngineInput {
    public static final long ARCHIVE_BYTES = 56030279L;
    public static final long LIBRARY_BYTES = 328419680L;
    public static final String ARCHIVE_SHA = "a8c0828ff8d0ccd7986006242dcf1e15e665ed62e8b2a9201dbd10972396e707";
    public static final String LIBRARY_SHA = "fac2be5b17ef79419ebd66fe4f9e362a48d7b2067d5e1f649e65c36edf2927e1";
    public static final AtomicBoolean IMPORTING = new AtomicBoolean();
    private EngineInput() {}

    public static File library(Context context) {
        return new File(context.getDir("engine-input", Context.MODE_PRIVATE), "libminecraftpe.so");
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    public static String sha256(InputStream input, long limit) throws IOException {
        MessageDigest hash = digest();
        byte[] buffer = new byte[65536];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            total += count;
            if (total > limit) throw new IOException("Input exceeds allowed size.");
            hash.update(buffer, 0, count);
        }
        return hex(hash.digest());
    }

    public static void verifyLibrary(File file) throws IOException {
        if (file.length() != LIBRARY_BYTES) throw new IOException("Import the supplied engine first.");
        try (InputStream input = new FileInputStream(file)) {
            if (!LIBRARY_SHA.equals(sha256(input, LIBRARY_BYTES))) {
                throw new IOException("Engine fingerprint mismatch; refusing native execution.");
            }
        }
    }

    @SuppressWarnings("deprecation")
    public static synchronized File importStream(Context context, InputStream source) throws IOException {
        File destination = library(context);
        File incoming = File.createTempFile("incoming-", ".tmp", destination.getParentFile());
        File extracted = null;
        try {
            MessageDigest hash = digest();
            long total = 0;
            byte[] buffer = new byte[65536];
            try (FileOutputStream output = new FileOutputStream(incoming)) {
                for (int count; (count = source.read(buffer)) != -1;) {
                    total += count;
                    if (total > LIBRARY_BYTES) throw new IOException("Selected input is too large.");
                    output.write(buffer, 0, count);
                    hash.update(buffer, 0, count);
                }
            }
            String fingerprint = hex(hash.digest());
            File ready;
            if (total == LIBRARY_BYTES && LIBRARY_SHA.equals(fingerprint)) {
                ready = incoming;
            } else if (total == ARCHIVE_BYTES && ARCHIVE_SHA.equals(fingerprint)) {
                extracted = File.createTempFile("engine-", ".tmp", destination.getParentFile());
                SevenZFileOptions options = SevenZFileOptions.builder().withMaxMemoryLimitInKb(262144).build();
                try (SevenZFile archive = new SevenZFile(incoming, options);
                     FileOutputStream output = new FileOutputStream(extracted)) {
                    SevenZArchiveEntry entry = archive.getNextEntry();
                    if (entry == null || entry.isDirectory() || !"libminecraftpe.so".equals(entry.getName())
                            || entry.getSize() != LIBRARY_BYTES) throw new IOException("Unexpected archive contents.");
                    long written = 0;
                    for (int count; (count = archive.read(buffer)) != -1;) {
                        written += count;
                        if (written > LIBRARY_BYTES) throw new IOException("Archive output exceeded expected size.");
                        output.write(buffer, 0, count);
                    }
                    if (written != LIBRARY_BYTES || archive.getNextEntry() != null) {
                        throw new IOException("Truncated archive or unexpected extra entries.");
                    }
                }
                verifyLibrary(extracted);
                ready = extracted;
            } else {
                throw new IOException("Not the supplied libminecraftpe.7z or its exact extracted .so. Nothing loaded.");
            }
            if (!ready.setReadOnly()) throw new IOException("Cannot make engine input read-only.");
            Files.move(ready.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally {
            Files.deleteIfExists(incoming.toPath());
            if (extracted != null) Files.deleteIfExists(extracted.toPath());
        }
    }
}
