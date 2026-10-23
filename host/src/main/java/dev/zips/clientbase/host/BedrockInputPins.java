package dev.zips.clientbase.host;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Match all supplied startup bytes without changing their internal DEX headers. */
final class BedrockInputPins {
    static final String[][] FILES = {
        {"AndroidManifest.xml", "27172", "d8039a55d0f5ee203f6b7273844213c94ae13de3b073d8f66df43e9dcd132fe5"},
        {"classes.dex", "6905400", "581a41725aa431887a5bfae23bb72afa7f80d9f97de89b187d59209a8cf35e94"},
        {"classes2.dex", "1655112", "2eca7cbd75a8b250ae9744e29a7f69534c26ec2f0671e1f0b49449abb50a15dc"},
        {"classes3.dex", "9336804", "31d0ab885d2e1a166d2e717d78b403397eb30fcb6ec186a6399c9970173e126a"},
        {"classes4.dex", "1802892", "11b43dbe9299bb7fae22e574af65764b686e6a734ffdb8fa638056c211cfe1fe"},
        {"classes5.dex", "36436", "4198dbbbf4700fb2274c84d2a98d14c974acafcaaa4552abc924106b62ff9531"},
        {"resources.arsc", "277104", "ef308a4464555dcd7e0acaf0e4c6cfec6b5ee371358f4b6f4374668c252d2363"}
    };
    static void verify(String apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            Set<String> expected = new HashSet<>();
            for (String[] pin : FILES) {
                expected.add(pin[0]);
                ZipEntry entry = zip.getEntry(pin[0]);
                if (entry == null || entry.getSize() != Long.parseLong(pin[1])) throw new IOException("Startup input size differs: " + pin[0]);
                try (InputStream in = zip.getInputStream(entry)) {
                    if (!pin[2].equals(EngineInput.sha256(in, Long.parseLong(pin[1])))) throw new IOException("Startup input hash differs: " + pin[0]);
                }
            }
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            Set<String> seen = new HashSet<>();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.matches("classes[0-9]*\\.dex") && !expected.contains(name)) throw new IOException("Unreviewed additional DEX: " + name);
                if (expected.contains(name) && !seen.add(name)) throw new IOException("Duplicate pinned ZIP entry: " + name);
            }
        }
    }
}
