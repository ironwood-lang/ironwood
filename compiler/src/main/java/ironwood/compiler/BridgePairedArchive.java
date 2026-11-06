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
        BridgeValuesLibrary.validate(metadata, entries);
        try {
            var generation = BridgeGeneration.fromManifest(metadata);
            if (!generation.managedHeap()) throw new IllegalArgumentException("paired native image requires objects-gc-v1");
            if (metadata.containsKey("native.target")) verifyBuild(generation, metadata, metadata.get("native.target"), "native.", "gc.config.sha256");
            else {
                var targets = metadata.keySet().stream().filter(key -> key.startsWith("target.") && key.endsWith(".build"))
                        .map(key -> key.substring(7, key.length() - 6)).toList();
                if (targets.isEmpty()) throw new IllegalArgumentException("missing native managed targets");
                for (String target : targets) verifyBuild(generation, metadata, target, "target." + target + ".", "target." + target + ".gc.config.sha256");
            }
            return new BridgePairedArchive(generation, metadata, entries);
        } catch (IllegalArgumentException invalid) { throw new IOException("incompatible paired image: " + invalid.getMessage(), invalid); }
    }

    private static void verifyBuild(BridgeGeneration generation, Map<String, String> metadata, String target, String prefix, String configuration) {
        var inputs = new TreeMap<String, String>();
        String inputPrefix = prefix + "input.";
        metadata.forEach((key, value) -> { if (key.startsWith(inputPrefix)) inputs.put(key.substring(inputPrefix.length()), value); });
        var build = generation.nativeBuild(target, inputs);
        if (!build.identity().equals(metadata.get(prefix + "build")) || !inputs.get("gc.config.sha256").equals(metadata.get(configuration)))
            throw new IllegalArgumentException("native collector configuration/build mismatch");
    }
}
