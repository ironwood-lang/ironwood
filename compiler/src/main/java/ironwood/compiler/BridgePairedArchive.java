// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Content consistency shared by assembly and companion packaging, not publisher authentication. */
record BridgePairedArchive(BridgeGeneration generation, Map<String, String> metadata, Map<String, byte[]> entries) {
    BridgePairedArchive { metadata = Map.copyOf(metadata); entries = Map.copyOf(entries); }

    static BridgePairedArchive read(Path path) throws IOException {
        var entries = new TreeMap<String, byte[]>();
        try (var zip = new ZipFile(path.toFile())) {
            for (var entry : zip.stream().toList()) {
                if (entry.isDirectory()) throw new IOException("unexpected directory entry in paired jar: " + entry.getName());
                try (var stream = zip.getInputStream(entry)) {
                    if (entries.putIfAbsent(entry.getName(), stream.readAllBytes()) != null) throw new IOException("duplicate paired jar entry: " + entry.getName());
                }
            }
        }
        byte[] bytes = entries.remove(BridgePackageManifest.PATH);
        if (bytes == null) throw new IOException("missing pairing manifest: " + path);
        var properties = new Properties(); properties.load(new ByteArrayInputStream(bytes));
        var metadata = new TreeMap<String, String>(); properties.forEach((key, value) -> metadata.put((String)key, (String)value));
        if (!Arrays.equals(bytes, BridgePackageManifest.serialize(metadata))) throw new IOException("noncanonical pairing manifest: " + path);
        for (var entry : entries.entrySet()) {
            if (!BridgeGeneration.bytesDigest(entry.getValue()).equals(metadata.get("content.sha256." + entry.getKey()))) {
                throw new IOException("paired content digest mismatch: " + entry.getKey());
            }
        }
        if (metadata.keySet().stream().filter(key -> key.startsWith("content.sha256.")).count() != entries.size()) {
            throw new IOException("paired content inventory mismatch: " + path);
        }
        return new BridgePairedArchive(BridgeGeneration.fromManifest(metadata), metadata, entries);
    }
}
