// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipFile;

final class BridgeAssemblyTests {
    static final String NAME = "Java Bridge assembly preserves paired bytes and rejects mixed or incomplete host artifacts";
    private BridgeAssemblyTests() {}

    static void assembly() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p6a/assembly").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path source = directory.resolve("Engine.iron"); Files.writeString(source, """
                package assembled;
                public final class Engine {
                    private Engine() {}
                    public static int add(int a, int b) { return a + b; }
                    public static void fail() { throw new IllegalStateException("assembled"); }
                }
                """);
        Path host = directory.resolve("host/engine.jar");
        BridgeProducerTests.command(directory, "produce", 0, new String[]{"--java-bridge", "--export", "assembled", "--unfreed=off", "-O3", "-o", host.toString(), source.toString()});
        Path output = directory.resolve("assembled.jar");
        assemble(directory, "assemble", 0, output, List.of(host));
        byte[] expected = Files.readAllBytes(output);
        assemble(directory, "reproduce", 0, output, List.of(host));
        check(java.util.Arrays.equals(expected, Files.readAllBytes(output)), "same-input assembly is not byte reproducible");
        var entries = entries(host); var properties = metadata(entries);
        String image = properties.get("native.resource");
        try (var zip = new ZipFile(output.toFile())) {
            check(java.util.Arrays.equals(zip.getInputStream(zip.getEntry(image)).readAllBytes(), entries.get(image)), "assembly changed native payload bytes");
        }
        Path consumer = directory.resolve("AssemblyConsumer.java"); Files.writeString(consumer, """
                import assembled.Engine;
                public final class AssemblyConsumer {
                    public static void main(String[] args) {
                        if (Engine.add(19, 23) != 42) throw new AssertionError();
                        try { Engine.fail(); throw new AssertionError(); }
                        catch (IllegalStateException expected) { if (!expected.getMessage().equals("assembled")) throw expected; }
                        if (Engine.add(20, 22) != 42) throw new AssertionError();
                        System.out.println("assembly-ok");
                    }
                }
                """);
        Path javaHome = Path.of(System.getProperty("java.home")), classes = directory.resolve("consumer-classes");
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-cp", output.toString(), "-d", classes.toString(), consumer.toString()), "consumer-javac");
        check(BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", output + System.getProperty("path.separator") + classes,
                "AssemblyConsumer"), "consumer").equals("assembly-ok\n"), "assembled consumer failed");
        assemble(directory, "duplicate", 1, output, List.of(host, host));
        check(java.util.Arrays.equals(expected, Files.readAllBytes(output)), "duplicate target replaced existing output");
        for (String scenario : List.of("payload", "generation", "build", "source", "license", "width", "image-digest", "declarations")) {
            var changed = new TreeMap<>(entries); var manifest = new TreeMap<>(properties);
            switch (scenario) {
                case "payload" -> changed.put(image, new byte[]{1, 2, 3});
                case "generation" -> manifest.put("program", "0".repeat(64));
                case "build" -> manifest.put("native.build", "0".repeat(64));
                case "source" -> {
                    String name = "META-INF/ironwood/java-sources/assembled/Engine.java";
                    changed.put(name, "changed source".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    manifest.put("content.sha256." + name, BridgeGeneration.bytesDigest(changed.get(name)));
                }
                case "license" -> {
                    String name = "META-INF/ironwood/licenses/LICENSE-MIT"; changed.remove(name); manifest.remove("content.sha256." + name);
                }
                case "width" -> manifest.put("native.pointer.bits", "32");
                case "image-digest" -> manifest.put("native.sha256", "0".repeat(64));
                case "declarations" -> manifest.remove("java.binding.0.descriptor");
                default -> throw new AssertionError(scenario);
            }
            changed.put(BridgePackageManifest.PATH, BridgePackageManifest.serialize(manifest));
            Path bad = directory.resolve("bad-" + scenario + ".jar"); BridgeJarArchive.publish(bad, changed);
            assemble(directory, "refuse-" + scenario, 1, output, List.of(bad));
            check(java.util.Arrays.equals(expected, Files.readAllBytes(output)), "failed assembly replaced output: " + scenario);
        }
        System.out.println("Bridge assembly evidence: " + directory);
    }

    private static void assemble(Path directory, String name, int status, Path output, List<Path> inputs) throws Exception {
        var args = new java.util.ArrayList<>(List.of("--java-bridge-assemble", "-o", output.toString()));
        inputs.forEach(input -> args.add(input.toString()));
        BridgeProducerTests.command(directory, name, status, args.toArray(String[]::new));
    }

    private static TreeMap<String, byte[]> entries(Path jar) throws Exception {
        var result = new TreeMap<String, byte[]>();
        try (var zip = new ZipFile(jar.toFile())) {
            for (var entry : zip.stream().toList()) try (var input = zip.getInputStream(entry)) { result.put(entry.getName(), input.readAllBytes()); }
        }
        return result;
    }

    private static TreeMap<String, String> metadata(TreeMap<String, byte[]> entries) throws Exception {
        var properties = new Properties(); properties.load(new ByteArrayInputStream(entries.get(BridgePackageManifest.PATH)));
        var result = new TreeMap<String, String>(); properties.forEach((key, value) -> result.put((String)key, (String)value)); return result;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
