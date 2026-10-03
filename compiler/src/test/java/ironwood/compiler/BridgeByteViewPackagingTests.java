// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.bridge.BridgeByteViewSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

final class BridgeByteViewPackagingTests {
    static final String NAME = "Java Bridge byte-view dependency binds exactly and composes across artifacts and modules";
    private BridgeByteViewPackagingTests() {}
    static void packaging() throws Exception {
        Path base = Path.of("workspace/java-bridge/byteviews/packaging").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path home = Path.of(System.getProperty("java.home"));
        var jars = new ArrayList<Path>(); var modules = new ArrayList<String>();
        for (String name : List.of("viewone", "viewtwo")) {
            Path source = directory.resolve(name + "/Kernel.iron"), jar = directory.resolve(name + ".jar");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package @PACKAGE@;
                    import ironwood.bridge.ByteView;
                    public final class Kernel {
                        private Kernel() {}
                        public static int value() { return 23; }
                        public static byte get(ByteView view) { return view.get(0); }
                        public static void put(ByteView view, byte value) { view.put(0, value); }
                    }
                    """.replace("@PACKAGE@", name));
            BridgeProducerTests.command(directory, name, 0, new String[]{"--java-bridge", "--export", name, "--unfreed=off", "-O3", "-o", jar.toString(), source.toString()});
            jars.add(jar);
            modules.add(BridgePairedArchive.read(jar).metadata().get("java.module"));
        }
        Path values = directory.resolve(BridgeByteViewSources.JAR_NAME);
        Path standalone = directory.resolve("standalone.jar");
        BridgeProducerTests.command(directory, "values", 0, new String[]{"--java-bridge-values", "-o", standalone.toString()});
        check(java.util.Arrays.equals(Files.readAllBytes(values), Files.readAllBytes(standalone)), "IDK and producer shared dependency differ");
        Path distribution = directory.resolve("distribution");
        BridgeDistributionCommand.distribute(jars.getFirst(), distribution, "example", "views", "1.0");
        check(Files.readString(distribution.resolve("views-1.0.pom")).contains("<artifactId>ironwood-bridge-values</artifactId>"), "missing Maven dependency");
        String version = BridgePairedArchive.read(jars.getFirst()).metadata().get("java.values.version");
        check(java.util.Arrays.equals(Files.readAllBytes(values), Files.readAllBytes(distribution.resolve("ironwood-bridge-values-" + version + ".jar"))), "distribution dependency changed");
        Path source = directory.resolve("Composition.java"), classes = directory.resolve("classes");
        Files.writeString(source, """
                import ironwood.bridge.ByteView;
                public class Composition {
                    public static void main(String[] args) {
                        ByteView view = ByteView.allocate(8).slice(2, 2);
                        viewone.Kernel.put(view, (byte)37);
                        if (viewtwo.Kernel.get(view) != 37) throw new AssertionError();
                        viewtwo.Kernel.put(view, (byte)-9);
                        if (viewone.Kernel.get(view) != -9) throw new AssertionError();
                        if (ByteView.class.getConstructors().length != 0 || ByteView.class.getFields().length != 0) throw new AssertionError();
                        java.util.Set<String> methods = new java.util.HashSet<>();
                        for (var method : ByteView.class.getDeclaredMethods()) {
                            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) methods.add(method.getName());
                        }
                        if (!methods.equals(java.util.Set.of("allocate", "slice", "asReadOnly", "length", "isReadOnly", "get", "put"))) throw new AssertionError();
                        System.out.println("composition-ok");
                    }
                }
                """);
        String cp = jars.get(0) + ":" + jars.get(1) + ":" + values;
        BridgeEntryTests.run(directory, List.of(home.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", cp,
                "-d", classes.toString(), source.toString()), "javac");
        check(BridgeEntryTests.run(directory, List.of(home.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", cp + ":" + classes,
                "Composition"), "classpath").equals("composition-ok\n"), "classpath composition failed");
        check(BridgeEntryTests.run(directory, List.of(home.resolve("bin/java").toString(), "-Xcheck:jni", "--module-path", cp,
                "--add-modules", String.join(",", modules) + ",ironwood.bridge.values", "-cp", classes.toString(), "Composition"), "modules")
                .equals("composition-ok\n"), "module-path composition failed");
        Path probe = directory.resolve("Probe.java");
        Files.writeString(probe, "public class Probe { public static void main(String[] args) { viewone.Kernel.value(); } }");
        BridgeEntryTests.run(directory, List.of(home.resolve("bin/javac").toString(), "--release", "21", "-cp", cp,
                "-d", classes.toString(), probe.toString()), "javac-probe");
        var wrong = new TreeMap<String, byte[]>();
        try (var zip = new ZipFile(values.toFile())) {
            for (var entry : zip.stream().toList()) try (var input = zip.getInputStream(entry)) { wrong.put(entry.getName(), input.readAllBytes()); }
        }
        wrong.put("extra-version-marker", new byte[]{1});
        Path incompatible = directory.resolve("incompatible.jar"); BridgeJarArchive.publish(incompatible, wrong);
        for (String mode : List.of("missing", "incompatible")) {
            Path temporary = directory.resolve("tmp-" + mode); Files.createDirectories(temporary);
            var command = List.of(home.resolve("bin/java").toString(), "-Xcheck:jni", "-Djava.io.tmpdir=" + temporary,
                    "-cp", jars.getFirst() + ":" + classes + (mode.equals("missing") ? "" : ":" + incompatible), "Probe");
            Path log = directory.resolve(mode + ".log");
            var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            check(process.waitFor() != 0 && Files.readString(log).contains("ironwood-bridge-values.jar"), "dependency failure is not actionable: " + mode);
            try (var files = Files.walk(temporary)) { check(files.noneMatch(Files::isRegularFile), "failed dependency extracted native payload"); }
        }
        // Refuse to overwrite an incompatible existing companion or its paired artifact.
        Path blocked = directory.resolve("blocked"); Files.createDirectories(blocked);
        Files.write(blocked.resolve(BridgeByteViewSources.JAR_NAME), new byte[]{3, 4});
        Path output = blocked.resolve("views.jar"); Files.write(output, new byte[]{5, 6});
        BridgeProducerTests.command(blocked, "preserve", 1, new String[]{"--java-bridge", "--export", "viewone", "--unfreed=off", "-o", output.toString(), directory.resolve("viewone/Kernel.iron").toString()});
        check(java.util.Arrays.equals(Files.readAllBytes(output), new byte[]{5, 6}), "failed production replaced previous artifact");
        check(java.util.Arrays.equals(Files.readAllBytes(blocked.resolve(BridgeByteViewSources.JAR_NAME)), new byte[]{3, 4}), "failed production replaced dependency");
        System.out.println("byte-view packaging evidence: " + directory);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
