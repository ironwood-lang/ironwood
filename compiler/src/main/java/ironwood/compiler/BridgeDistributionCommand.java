// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.io.IOException;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Standard local Maven artifacts from an already paired jar, without upload or native modification. */
final class BridgeDistributionCommand {
    private BridgeDistributionCommand() {}

    static int run(String[] arguments, PrintStream out, PrintStream err) {
        try {
            var options = new TreeMap<String, String>();
            for (int i = 0; i < arguments.length; i++) {
                String option = arguments[i];
                if (option.equals("--java-bridge-distribution")) continue;
                if (!List.of("--input", "--group-id", "--artifact-id", "--version", "-d").contains(option)
                        || ++i >= arguments.length || options.putIfAbsent(option, arguments[i]) != null) {
                    throw new IllegalArgumentException("usage: ironwoodc --java-bridge-distribution --input paired.jar --group-id group --artifact-id name --version version -d new-directory");
                }
            }
            if (options.size() != 5) throw new IllegalArgumentException("distribution requires input, explicit Maven coordinates and a new output directory");
            Path destination = Path.of(options.get("-d"));
            distribute(Path.of(options.get("--input")), destination, options.get("--group-id"), options.get("--artifact-id"), options.get("--version"));
            out.println("packaged " + destination.toAbsolutePath().normalize()); return 0;
        } catch (IOException | IllegalArgumentException failure) {
            err.println("error: Java Bridge distribution failed: " + failure.getMessage()); return 1;
        }
    }

    static void distribute(Path input, Path output, String group, String artifact, String version) throws IOException {
        if (group == null || !group.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
                || !coordinate(artifact) || !coordinate(version)) throw new IOException("invalid Maven coordinates");
        Path destination = output.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("distribution output already exists: " + destination);
        Files.createDirectories(destination.getParent()); Path stage = Files.createTempDirectory(destination.getParent(), ".ironwood-bridge-distribution-");
        String basename = artifact + "-" + version;
        try {
            Path main = stage.resolve(basename + ".jar"); Files.copy(input, main);
            var paired = BridgePairedArchive.read(main);
            var sources = companion(paired, "META-INF/ironwood/java-sources/");
            var docs = companion(paired, "META-INF/ironwood/javadoc/");
            if (sources.keySet().stream().noneMatch(name -> name.endsWith(".java")) || !docs.containsKey("index.html")) {
                throw new IOException("paired artifact lacks generated Java sources/Javadoc");
            }
            Path sourceJar = stage.resolve(basename + "-sources.jar"), docJar = stage.resolve(basename + "-javadoc.jar");
            BridgeJarArchive.publish(sourceJar, sources); BridgeJarArchive.publish(docJar, docs);
            String dependencies = BridgeValuesLibrary.distribute(paired, stage);
            Path pom = stage.resolve(basename + ".pom");
            Files.writeString(pom, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>@GROUP@</groupId><artifactId>@ARTIFACT@</artifactId><version>@VERSION@</version>
                      <packaging>jar</packaging>
                      <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                        <ironwood.bridge.generation>@GENERATION@</ironwood.bridge.generation>
                      </properties>
                      @DEPENDENCIES@
                    </project>
                    """.replace("@GROUP@", group).replace("@ARTIFACT@", artifact).replace("@VERSION@", version)
                    .replace("@GENERATION@", paired.generation().identity()).replace("@DEPENDENCIES@", dependencies));
            var inventory = new TreeMap<String, String>();
            inventory.put("generation", paired.generation().identity()); inventory.put("api", paired.generation().apiIdentity());
            inventory.put("maven.coordinates", group + ":" + artifact + ":" + version);
            try (var files = Files.list(stage)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    inventory.put("sha256." + file.getFileName(), BridgeGeneration.bytesDigest(Files.readAllBytes(file)));
                }
            }
            Files.write(stage.resolve("bridge-distribution.properties"), BridgePackageManifest.serialize(inventory));
            // A new destination is required; do not replace a previous distribution.
            Files.move(stage, destination);
        } finally {
            if (Files.exists(stage)) try (var files = Files.walk(stage)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static boolean coordinate(String value) {
        return value != null && value.matches("[A-Za-z0-9_][A-Za-z0-9_.+-]*");
    }

    private static Map<String, byte[]> companion(BridgePairedArchive paired, String prefix) throws IOException {
        var result = new TreeMap<String, byte[]>();
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Ironwood-Bridge-Generation", paired.generation().identity());
        var bytes = new ByteArrayOutputStream(); manifest.write(bytes);
        result.put("META-INF/MANIFEST.MF", bytes.toByteArray());
        for (var entry : paired.entries().entrySet()) {
            String name = entry.getKey();
            if (name.startsWith(prefix)) {
                if (result.putIfAbsent(name.substring(prefix.length()), entry.getValue()) != null) throw new IOException("duplicate companion entry: " + name);
            } else if (name.startsWith("META-INF/ironwood/licenses/")) result.put(name, entry.getValue());
        }
        return result;
    }
}
