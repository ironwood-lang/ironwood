// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Verifies the complete staged jar before a single atomic publication. */
final class BridgeJarArchive {
    private BridgeJarArchive() {}
    private static final String MANIFEST = "META-INF/MANIFEST.MF";

    static void publish(Path output, Map<String, byte[]> content) throws IOException {
        var entries = new TreeMap<String, byte[]>();
        for (var entry : content.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0
                    || java.util.Arrays.stream(name.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
                throw new IOException("invalid Java Bridge jar entry: " + name);
            }
            if (entry.getValue() == null) throw new IOException("missing Java Bridge jar content: " + name);
            entries.put(name, entry.getValue().clone());
        }
        byte[] manifest = entries.get(MANIFEST);
        if (manifest == null || !"1.0".equals(new Manifest(new ByteArrayInputStream(manifest)).getMainAttributes().getValue("Manifest-Version"))) {
            throw new IOException("Java Bridge jar requires a valid versioned Java manifest");
        }
        Path destination = output.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Java Bridge output is not a regular file: " + destination);
        }
        Files.createDirectories(destination.getParent());
        Path staged = Files.createTempFile(destination.getParent(), ".ironwood-bridge-", ".jar");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(staged))) {
                write(zip, MANIFEST, manifest);
                for (var entry : entries.entrySet()) if (!entry.getKey().equals(MANIFEST)) write(zip, entry.getKey(), entry.getValue());
            }
            try (var zip = new ZipFile(staged.toFile())) {
                if (zip.size() != entries.size()) throw new IOException("Java Bridge staged jar entry count differs");
                for (var entry : entries.entrySet()) {
                    var stored = zip.getEntry(entry.getKey());
                    if (stored == null || stored.getSize() != entry.getValue().length) throw new IOException("Java Bridge staged entry missing/truncated: " + entry.getKey());
                    try (var input = zip.getInputStream(stored)) {
                        if (!BridgeGeneration.bytesDigest(input.readAllBytes()).equals(BridgeGeneration.bytesDigest(entry.getValue()))) {
                            throw new IOException("Java Bridge staged entry digest differs: " + entry.getKey());
                        }
                    }
                }
            }
            // No non-atomic fallback: a failed build must preserve an earlier jar.
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void write(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        var entry = new ZipEntry(name); entry.setTime(0);
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
}
