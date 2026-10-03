// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.RuntimeLibrary;
import ironwood.compiler.bridge.BridgeGeneration;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Content inventory of the actual producer, independent of jar timestamps and host paths. */
record BridgeProducerInputs(String compilerVersion, Map<String, String> compilerFiles,
                            Map<String, String> runtimeFiles) {
    BridgeProducerInputs {
        compilerFiles = Collections.unmodifiableMap(new TreeMap<>(compilerFiles));
        runtimeFiles = Collections.unmodifiableMap(new TreeMap<>(runtimeFiles));
    }

    String compilerIdentity() { return BridgeGeneration.contentIdentity(compilerFiles); }
    String runtimeIdentity() { return BridgeGeneration.contentIdentity(runtimeFiles); }

    static BridgeProducerInputs discover() throws IOException {
        var runtime = RuntimeLibrary.discover();
        if (!runtime.successful()) throw new IOException(runtime.error());
        try {
            Path location = Path.of(BridgeProducerInputs.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            return read(CompilerVersion.current(), location, runtime.source().orElseThrow().getParent().getParent());
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IOException("cannot identify the Java Bridge compiler inputs", exception);
        }
    }

    static BridgeProducerInputs read(String version, Path compiler, Path runtime) throws IOException {
        var compilerFiles = new TreeMap<String, String>();
        if (Files.isDirectory(compiler)) {
            try (var files = Files.walk(compiler.resolve("ironwood/compiler"))) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    addCompilerFile(compilerFiles, relative(compiler, file), Files.readAllBytes(file), version);
                }
            }
        } else {
            try (var jar = new ZipFile(compiler.toFile())) {
                for (var entry : jar.stream().filter(entry -> !entry.isDirectory()
                        && entry.getName().startsWith("ironwood/compiler/")).toList()) {
                    try (var input = jar.getInputStream(entry)) {
                        addCompilerFile(compilerFiles, entry.getName(), input.readAllBytes(), version);
                    }
                }
            }
        }
        if (!compilerFiles.containsKey("ironwood/compiler/Main.class")
                || !compilerFiles.containsKey("ironwood/compiler/VERSION") || version == null || version.equals("unknown")) {
            throw new IOException("Java Bridge requires an identified complete compiler build");
        }
        var runtimeFiles = new TreeMap<String, String>();
        try (var files = Files.walk(runtime)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".c") || file.toString().endsWith(".h")).sorted().toList()) {
                runtimeFiles.put(relative(runtime, file), BridgeGeneration.bytesDigest(Files.readAllBytes(file)));
            }
        }
        for (String required : new String[]{"src/ironwood_runtime.c", "include/ironwood_runtime.h", "include/ironwood_bridge.h"}) {
            if (!runtimeFiles.containsKey(required)) throw new IOException("missing Java Bridge runtime input: " + required);
        }
        return new BridgeProducerInputs(version, compilerFiles, runtimeFiles);
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }

    private static void addCompilerFile(Map<String, String> files, String name, byte[] bytes, String version) throws IOException {
        if (name.equals("ironwood/compiler/VERSION")
                && !new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim().equals(version)) {
            throw new IOException("Java Bridge compiler version does not match its input inventory");
        }
        if (files.putIfAbsent(name, BridgeGeneration.bytesDigest(bytes)) != null) {
            throw new IOException("duplicate compiler input '" + name + "'");
        }
    }
}
