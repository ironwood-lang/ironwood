// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.JarFile;

final class BridgeCompanionTests {
    static final String NAME = "Java Bridge distribution preserves paired jars and produces reproducible IDE companions";
    private BridgeCompanionTests() {}

    static void distribution() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p6a/distribution").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path source = directory.resolve("Engine.iron"); Files.writeString(source, """
                package distributed;
                public final class Engine {
                    private Engine() {}
                    public static int add(int a, int b) { return a + b; }
                }
                """);
        Path host = directory.resolve("host/engine.jar"), assembled = directory.resolve("assembled.jar");
        BridgeProducerTests.command(directory, "produce", 0, new String[]{"--java-bridge", "--export", "distributed", "-O3", "-o", host.toString(), source.toString()});
        BridgeProducerTests.command(directory, "assemble", 0, new String[]{"--java-bridge-assemble", "-o", assembled.toString(), host.toString()});
        for (Path input : List.of(host, assembled)) {
            Path output = directory.resolve(input == host ? "host-distribution" : "assembled-distribution");
            distribute(directory, "package-" + output.getFileName(), 0, input, output, "org.ironwood.example");
            Path repeat = directory.resolve(output.getFileName() + "-repeat");
            distribute(directory, "repeat-" + output.getFileName(), 0, input, repeat, "org.ironwood.example");
            var paired = BridgePairedArchive.read(input);
            check(Arrays.equals(Files.readAllBytes(input), Files.readAllBytes(output.resolve("engine-1.0.jar"))), "main artifact changed");
            try (var files = Files.list(output)) {
                for (Path file : files.toList()) check(Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(repeat.resolve(file.getFileName()))), "distribution is not reproducible: " + file);
            }
            var inventory = new Properties(); inventory.load(new ByteArrayInputStream(Files.readAllBytes(output.resolve("bridge-distribution.properties"))));
            for (String name : List.of("engine-1.0.jar", "engine-1.0-sources.jar", "engine-1.0-javadoc.jar", "engine-1.0.pom")) {
                check(BridgeGeneration.bytesDigest(Files.readAllBytes(output.resolve(name))).equals(inventory.getProperty("sha256." + name)), "distribution digest: " + name);
            }
            for (String kind : List.of("sources", "javadoc")) {
                String prefix = "META-INF/ironwood/" + (kind.equals("sources") ? "java-sources" : "javadoc") + "/";
                try (var jar = new JarFile(output.resolve("engine-1.0-" + kind + ".jar").toFile())) {
                    check(paired.generation().identity().equals(jar.getManifest().getMainAttributes().getValue("Ironwood-Bridge-Generation")), "companion generation");
                    int expected = 1;
                    for (var entry : paired.entries().entrySet()) {
                        String name = entry.getKey();
                        if (!name.startsWith(prefix) && !name.startsWith("META-INF/ironwood/licenses/")) continue;
                        String target = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
                        check(jar.getEntry(target) != null, "missing companion entry: " + target);
                        try (var stream = jar.getInputStream(jar.getEntry(target))) { check(Arrays.equals(entry.getValue(), stream.readAllBytes()), "changed companion bytes: " + target); }
                        expected++;
                    }
                    check(jar.size() == expected, "unexpected companion content");
                }
            }
            String pom = Files.readString(output.resolve("engine-1.0.pom"));
            check(pom.contains("<groupId>org.ironwood.example</groupId>") && pom.contains("<artifactId>engine</artifactId>")
                    && pom.contains("<version>1.0</version>") && pom.contains(paired.generation().identity()), "POM coordinates/generation");
            distribute(directory, "refuse-existing-" + output.getFileName(), 1, input, output, "org.ironwood.example");
            check(Arrays.equals(Files.readAllBytes(input), Files.readAllBytes(output.resolve("engine-1.0.jar"))), "existing output changed");
        }
        var paired = BridgePairedArchive.read(host); var changed = new TreeMap<>(paired.entries());
        changed.put(BridgePackageManifest.PATH, BridgePackageManifest.serialize(paired.metadata()));
        changed.put("META-INF/ironwood/java-sources/distributed/Engine.java", new byte[]{1});
        Path corrupt = directory.resolve("corrupt.jar"); BridgeJarArchive.publish(corrupt, changed);
        Path rejected = directory.resolve("rejected");
        distribute(directory, "refuse-corrupt", 1, corrupt, rejected, "org.ironwood.example");
        check(!Files.exists(rejected), "corrupt artifact published");
        distribute(directory, "refuse-coordinates", 1, host, rejected, "../escape");
        check(!Files.exists(rejected), "invalid coordinates published");
        changed = new TreeMap<>(paired.entries()); var metadata = new TreeMap<>(paired.metadata());
        changed.keySet().removeIf(name -> name.startsWith("META-INF/ironwood/javadoc/"));
        metadata.keySet().removeIf(name -> name.startsWith("content.sha256.META-INF/ironwood/javadoc/"));
        changed.put(BridgePackageManifest.PATH, BridgePackageManifest.serialize(metadata));
        Path incomplete = directory.resolve("incomplete.jar"); BridgeJarArchive.publish(incomplete, changed);
        distribute(directory, "refuse-incomplete", 1, incomplete, rejected, "org.ironwood.example");
        check(!Files.exists(rejected), "missing Javadoc published");
        try (var files = Files.list(directory)) { check(files.noneMatch(path -> path.getFileName().toString().startsWith(".ironwood-bridge-distribution-")), "failed staging directory leaked"); }
        System.out.println("Bridge distribution evidence: " + directory);
    }

    private static void distribute(Path directory, String name, int status, Path input, Path output, String group) throws Exception {
        BridgeProducerTests.command(directory, name, status, new String[]{"--java-bridge-distribution", "--input", input.toString(),
                "--group-id", group, "--artifact-id", "engine", "--version", "1.0", "-d", output.toString()});
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
