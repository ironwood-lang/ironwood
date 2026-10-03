// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.bridge.BridgeByteViewSources;
import ironwood.compiler.backend.RuntimeLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic, host-only dependency shared across independent native worlds. */
final class BridgeValuesLibrary {
    private BridgeValuesLibrary() {}
    static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        if (args.length != 3 || !args[0].equals("--java-bridge-values") || !args[1].equals("-o")) {
            err.println("usage: ironwoodc --java-bridge-values -o ironwood-bridge-values.jar"); return 2;
        }
        try {
            build(Path.of(args[2]), err);
            out.println("built " + Path.of(args[2]).toAbsolutePath().normalize()); return 0;
        } catch (IOException failure) { err.println("error: " + failure.getMessage()); return 1; }
    }

    static void build(Path output, java.io.PrintStream diagnostics) throws IOException {
        Path destination = output.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Path stage = Files.createTempDirectory(destination.getParent(), ".ironwood-values-build-");
        try {
            Path source = stage.resolve(BridgeByteViewSources.SOURCE_PATH), classes = stage.resolve("classes");
            Files.createDirectories(source.getParent()); Files.createDirectories(classes);
            Files.writeString(source, BridgeByteViewSources.SOURCE);
            BridgeBuildTools.java(List.of(source), classes, diagnostics);
            Path home = RuntimeLibrary.discover().source().orElseThrow(() -> new IOException("missing runtime distribution"))
                    .getParent().getParent().getParent();
            var licenses = new TreeMap<String, byte[]>();
            for (String name : List.of("LICENSE", "LICENSE-MIT", "LICENSE-APACHE")) {
                licenses.put("META-INF/ironwood/licenses/" + name, Files.readAllBytes(home.resolve(name)));
            }
            byte[] bytes = packageClass(stage, Files.readAllBytes(classes.resolve(BridgeByteViewSources.CLASS_PATH)), licenses);
            copy(stage.resolve(BridgeByteViewSources.JAR_NAME), destination, bytes);
        } finally {
            try (var files = Files.walk(stage)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    static byte[] packageClass(Path stage, byte[] bytecode, Map<String, byte[]> distribution) throws IOException {
        var entries = new TreeMap<String, byte[]>();
        entries.put("META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\r\nAutomatic-Module-Name: ironwood.bridge.values\r\n"
                + "Implementation-Version: " + CompilerVersion.current() + "\r\nIronwood-ByteView-ABI: "
                + BridgeByteViewSources.ABI + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        entries.put(BridgeByteViewSources.CLASS_PATH, bytecode);
        entries.put("META-INF/ironwood/java-sources/" + BridgeByteViewSources.SOURCE_PATH,
                BridgeByteViewSources.SOURCE.getBytes(StandardCharsets.UTF_8));
        for (String name : List.of("LICENSE", "LICENSE-MIT", "LICENSE-APACHE")) {
            String entry = "META-INF/ironwood/licenses/" + name;
            byte[] content = distribution.get(entry);
            if (content == null) throw new IOException("missing shared Java support license: " + name);
            entries.put(entry, content);
        }
        Path jar = stage.resolve(BridgeByteViewSources.JAR_NAME);
        BridgeJarArchive.publish(jar, entries);
        return Files.readAllBytes(jar);
    }

    static void validate(Map<String, String> metadata, Map<String, byte[]> entries) throws IOException {
        byte[] bytes = entries.get(BridgeByteViewSources.RESOURCE);
        if (bytes == null) {
            if (metadata.keySet().stream().anyMatch(key -> key.startsWith("java.values."))) {
                throw new IOException("missing paired shared Java dependency");
            }
            return;
        }
        String version = metadata.get("java.values.version");
        if (!BridgeByteViewSources.ABI.equals(metadata.get("java.values.abi"))
                || version == null || !version.matches("[A-Za-z0-9_][A-Za-z0-9_.+-]*")
                || !ironwood.compiler.bridge.BridgeGeneration.bytesDigest(bytes).equals(metadata.get("java.values.sha256"))) {
            throw new IOException("shared Java dependency identity mismatch");
        }
        try (var zip = new java.util.jar.JarInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var manifest = zip.getManifest();
            if (manifest == null || !version.equals(manifest.getMainAttributes().getValue("Implementation-Version"))
                    || !BridgeByteViewSources.ABI.equals(manifest.getMainAttributes().getValue("Ironwood-ByteView-ABI"))
                    || !"ironwood.bridge.values".equals(manifest.getMainAttributes().getValue("Automatic-Module-Name"))) {
                throw new IOException("shared Java dependency manifest mismatch");
            }
        }
    }

    static String distribute(BridgePairedArchive paired, Path stage) throws IOException {
        byte[] bytes = paired.entries().get(BridgeByteViewSources.RESOURCE);
        if (bytes == null) return "";
        String version = paired.metadata().get("java.values.version");
        String base = "ironwood-bridge-values-" + version;
        Files.write(stage.resolve(base + ".jar"), bytes);
        var sources = new TreeMap<String, byte[]>();
        sources.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                String prefix = "META-INF/ironwood/java-sources/";
                if (name.startsWith(prefix)) sources.put(name.substring(prefix.length()), zip.readAllBytes());
                else if (name.startsWith("META-INF/ironwood/licenses/")) sources.put(name, zip.readAllBytes());
            }
        }
        BridgeJarArchive.publish(stage.resolve(base + "-sources.jar"), sources);
        Files.writeString(stage.resolve(base + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>org.ironwood</groupId>
                  <artifactId>ironwood-bridge-values</artifactId><version>@VERSION@</version>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                </project>
                """.replace("@VERSION@", version));
        return "<dependencies><dependency><groupId>org.ironwood</groupId>"
                + "<artifactId>ironwood-bridge-values</artifactId><version>" + version
                + "</version></dependency></dependencies>";
    }

    static void checkDestination(Path destination, byte[] bytes) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                || !java.util.Arrays.equals(Files.readAllBytes(destination), bytes))) {
            throw new IOException("existing shared byte-view dependency differs; choose a separate output directory: " + destination);
        }
    }

    static void copy(Path staged, Path destination, byte[] bytes) throws IOException {
        checkDestination(destination, bytes);
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) Files.copy(staged, destination);
    }
}
