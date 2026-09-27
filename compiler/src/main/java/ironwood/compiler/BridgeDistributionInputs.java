// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.RuntimeLibrary;
import ironwood.compiler.bridge.BridgeGeneration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact library source and distribution notices carried with a generated native jar. */
final class BridgeDistributionInputs {
    private final Map<String, byte[]> entries;
    private final Map<String, String> hashes;

    private BridgeDistributionInputs(Map<String, byte[]> entries) {
        var copy = new TreeMap<String, byte[]>();
        entries.forEach((name, bytes) -> copy.put(name, bytes.clone()));
        this.entries = Collections.unmodifiableMap(copy);
        var hashes = new TreeMap<String, String>();
        entries.forEach((name, bytes) -> hashes.put(name, BridgeGeneration.bytesDigest(bytes)));
        this.hashes = Collections.unmodifiableMap(hashes);
    }

    Map<String, byte[]> entries() {
        var copy = new TreeMap<String, byte[]>();
        entries.forEach((name, bytes) -> copy.put(name, bytes.clone()));
        return Collections.unmodifiableMap(copy);
    }

    Map<String, String> hashes() { return hashes; }
    String identity() { return BridgeGeneration.contentIdentity(hashes); }

    static BridgeDistributionInputs discover(CompilationArtifact artifact) throws IOException {
        var runtime = RuntimeLibrary.discover();
        if (!runtime.successful()) throw new IOException(runtime.error());
        Path home = runtime.source().orElseThrow().getParent().getParent().getParent();
        return read(home, artifact, StandardLibrary.discover());
    }

    static BridgeDistributionInputs read(Path home, CompilationArtifact artifact, StandardLibrary library) throws IOException {
        if (!artifact.valid() || artifact.bridgeApiFacts().isEmpty()
                || !artifact.bridgeApiFacts().orElseThrow().matches(artifact.program().orElseThrow())) {
            throw new IOException("Java Bridge source packaging requires the analyzed program's API inventory");
        }
        var entries = new TreeMap<String, byte[]>();
        for (String name : List.of("LICENSE", "LICENSE-MIT", "LICENSE-APACHE", "LICENSES/GPL-2.0-only.txt",
                "LICENSES/Classpath-exception-2.0.txt", "LICENSES/Unicode-15.0.txt", "LICENSES/MPL-2.0.txt")) {
            addFile(entries, "META-INF/ironwood/licenses/" + name, home.resolve(name));
        }
        for (String name : List.of("LICENSE_MECHANICS", "THIRD_PARTY_NOTICES.md", "SOURCE_PROVENANCE.md")) {
            Path file = home.resolve("docs").resolve(name);
            if (!Files.isRegularFile(file)) file = home.resolve(name);
            addFile(entries, "META-INF/ironwood/licenses/" + name, file);
        }
        Path runtime = home.resolve("runtime");
        try (var files = Files.walk(runtime)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".c") || file.toString().endsWith(".h")).sorted().toList()) {
                addFile(entries, "META-INF/ironwood/source/runtime/" + runtime.relativize(file).toString().replace(java.io.File.separatorChar, '/'), file);
            }
        }
        for (String required : List.of("src/ironwood_runtime.c", "include/ironwood_runtime.h", "include/ironwood_bridge.h")) {
            if (!entries.containsKey("META-INF/ironwood/source/runtime/" + required)) throw new IOException("missing Java Bridge runtime source: " + required);
        }
        // Use the source reconstructed from actual analyzed library inputs. The
        // distribution's source tree may be newer than an installed .ironjar.
        for (var type : artifact.bridgeApiFacts().orElseThrow().types().values()) {
            if (!library.isBundledSource(type.source())) continue;
            String name = type.source().path().getFileName().toString();
            if (!name.endsWith(".iron") || name.contains("\\") || name.equals(".iron")) {
                throw new IOException("cannot identify corresponding library source: " + type.source().path());
            }
            String entry = "META-INF/ironwood/source/stdlib/" + type.packageName().replace('.', '/') + "/" + name;
            byte[] bytes = type.source().content().getBytes(StandardCharsets.UTF_8);
            byte[] previous = entries.putIfAbsent(entry, bytes);
            if (previous != null && !java.util.Arrays.equals(previous, bytes)) throw new IOException("conflicting corresponding library source: " + entry);
        }
        if (entries.keySet().stream().noneMatch(name -> name.startsWith("META-INF/ironwood/source/stdlib/"))) {
            throw new IOException("cannot identify corresponding Java Bridge standard-library source");
        }
        return new BridgeDistributionInputs(entries);
    }

    private static void addFile(Map<String, byte[]> entries, String name, Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("missing Java Bridge distribution input: " + file);
        if (entries.putIfAbsent(name, Files.readAllBytes(file)) != null) throw new IOException("duplicate Java Bridge distribution input: " + name);
    }
}
