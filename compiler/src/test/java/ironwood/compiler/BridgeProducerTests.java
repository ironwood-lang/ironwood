// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

final class BridgeProducerTests {
    static final String NAME = "Java Bridge producer publishes paired jars with source parity and failure preservation";
    private BridgeProducerTests() {}

    private static final String SOURCE = """
            package produced;
            public final class Engine {
                private Engine() {}
                private static int entered;
                public static final int CONSTANT = 42;
                public static int add(int a, int b) { return a + b; }
                public static String echo(String value) { return value; }
                public static String fresh(String a, String b) { entered++; return new String(a); }
                public static String fixed() { return "fixed"; }
                public static int calls() { return entered; }
                public static int fail() throws ironwood.io.IOException { throw new ironwood.io.IOException("producer failure"); }
                public static final class Lazy {
                    private Lazy() {}
                    private static int value = initialize();
                    private static int initialize() { throw new IllegalStateException("lazy failure"); }
                    public static int read() { return value; }
                }
            }
            """;

    static void producer() throws Exception {
        if (!System.getProperty("os.name").equals("Mac OS X")) return;
        Path base = Path.of("workspace/java-bridge/evidence/p2/producer").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path source = directory.resolve("source/produced/Engine.iron"); Files.createDirectories(source.getParent()); Files.writeString(source, SOURCE);
        Path classes = directory.resolve("iron-classes"), archive = directory.resolve("input.ironjar");
        command(directory, "compile-iron", 0, new String[]{"--unfreed=off", "-d", classes.toString(), source.toString()});
        IronJar.create(archive, List.of(classes));
        Properties reference = null; Path firstJar = null;
        for (String variant : List.of("source", "classes", "archive")) {
            Path folder = directory.resolve(variant); Files.createDirectories(folder);
            Path jar = folder.resolve("engine.jar");
            var arguments = new ArrayList<>(List.of("--java-bridge", "--export", "produced", "--unfreed=off", "-o", jar.toString(), variant.equals("source") ? "-O0" : "-O3"));
            if (variant.equals("source")) arguments.add(source.toString());
            else arguments.addAll(List.of("-cp", (variant.equals("classes") ? classes : archive).toString()));
            command(folder, "producer", 0, arguments.toArray(String[]::new));
            var manifest = inspect(jar);
            if (reference == null) { reference = manifest; firstJar = jar; }
            else for (String key : List.of("generation", "api", "java.module", "program")) {
                check(manifest.getProperty(key).equals(reference.getProperty(key)), "source/class/archive mismatch: " + key);
            }
            consumer(folder, jar, manifest);
        }
        check(reference != null && firstJar != null, "missing producer output");
        byte[] previous = Files.readAllBytes(firstJar);
        for (String body : List.of("public static Object unsupported() { return null; }",
                "private static String stored; public static String capture(String input) { stored = input; return input; }",
                "public static int unsafe() { String value = new String(\"x\"); free value; return value.length(); }")) {
            Files.writeString(source, SOURCE.replace("private Engine() {}", "private Engine() {} " + body));
            for (var mode : UnfreedMode.values()) {
                command(directory, "reject-" + Math.abs(body.hashCode()) + "-" + mode, 1,
                        new String[]{"--java-bridge", "--export", "produced", "--unfreed=" + mode.name().toLowerCase(java.util.Locale.ROOT),
                                "-o", firstJar.toString(), source.toString()});
                check(java.util.Arrays.equals(Files.readAllBytes(firstJar), previous), "rejected program replaced earlier jar");
            }
        }
        Files.writeString(source, SOURCE);
        for (String[] arguments : List.of(new String[]{"--java-bridge"}, new String[]{"--java-bridge", "--export"},
                new String[]{"--java-bridge", "--export", "produced", "-o", "/"},
                new String[]{"--java-bridge", "--export", "produced", "-o", firstJar.toString(), "--link"},
                new String[]{"--java-bridge", "--export", "produced", "-o", firstJar.toString(), "--main-class", "produced.Engine"})) {
            command(directory, "usage-" + Math.abs(java.util.Arrays.hashCode(arguments)), 2, arguments);
            check(java.util.Arrays.equals(Files.readAllBytes(firstJar), previous), "invalid options changed output");
        }
        // Fail after staging and target discovery, at the adapter compilation step.
        var toolchain = ironwood.compiler.backend.LlvmToolchain.discover(null).toolchain().orElseThrow();
        Path fake = directory.resolve("fault-llvm/bin"); Files.createDirectories(fake);
        for (String tool : List.of("llvm-as", "opt", "llc", "llvm-objcopy", "llvm-config")) Files.createSymbolicLink(fake.resolve(tool), toolchain.home().resolve("bin").resolve(tool));
        Path clang = fake.resolve("clang");
        Files.writeString(clang, "#!/bin/sh\nfor option do\n if [ \"$option\" = -c ]; then echo 'injected adapter compiler failure'; exit 9; fi\ndone\nexec '"
                + toolchain.clang().toString().replace("'", "'\\''") + "' \"$@\"\n");
        check(clang.toFile().setExecutable(true, true), "cannot prepare compiler fault fixture");
        String failure = command(directory, "compiler-failure", 1, new String[]{"--java-bridge", "--export", "produced", "--unfreed=off",
                "--llvm-home", fake.getParent().toString(), "-o", firstJar.toString(), source.toString()});
        check(failure.contains("injected adapter compiler failure") && java.util.Arrays.equals(Files.readAllBytes(firstJar), previous), "staged compiler failure changed output");
        try (var paths = Files.list(firstJar.getParent())) { check(paths.noneMatch(path -> path.getFileName().toString().startsWith(".ironwood-bridge-")), "producer left staged files"); }
        Files.writeString(directory.resolve("revision.txt"), "source/class/archive production pipeline; actual compiler/runtime inventories in each manifest\n");
        System.out.println("Java Bridge producer evidence: " + directory);
    }

    private static Properties inspect(Path jar) throws Exception {
        var properties = new Properties();
        try (var zip = new ZipFile(jar.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) { properties.load(input); }
            check(properties.getProperty("java.supported").equals("21,22,23") && properties.getProperty("native.target").equals("macos-arm64"), "incorrect support manifest");
            for (var entry : zip.stream().toList()) {
                if (entry.getName().equals(BridgePackageManifest.PATH)) continue;
                try (var input = zip.getInputStream(entry)) {
                    check(BridgeGeneration.bytesDigest(input.readAllBytes()).equals(properties.getProperty("content.sha256." + entry.getName())), "jar content not paired: " + entry.getName());
                }
            }
            for (String required : List.of("META-INF/ironwood/java-sources/produced/Engine.java", "META-INF/ironwood/javadoc/produced/Engine.html",
                    "META-INF/ironwood/javadoc/legal/LICENSE", "META-INF/ironwood/licenses/LICENSE-MIT", "META-INF/ironwood/licenses/SOURCE_PROVENANCE.md",
                    "META-INF/ironwood/source/runtime/include/ironwood_bridge.h", "META-INF/ironwood/source/stdlib/ironwood/lang/String.iron")) {
                check(zip.getEntry(required) != null, "missing packaged source/notice: " + required);
            }
            check(zip.stream().noneMatch(entry -> entry.getName().contains("source/stdlib/produced")), "application source silently packaged");
        }
        return properties;
    }

    private static void consumer(Path folder, Path jar, Properties manifest) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home")), source = folder.resolve("Consumer.java"), classes = folder.resolve("consumer-classes");
        Files.writeString(source, CONSUMER);
        BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-cp", jar.toString(), "-d", classes.toString(), source.toString()), "consumer-javac");
        Path executable = folder.resolve("consumer.jar");
        Path mainManifest = folder.resolve("consumer.mf");
        Files.writeString(mainManifest, "Manifest-Version: 1.0\nMain-Class: Consumer\nClass-Path: engine.jar\n\n");
        BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/jar").toString(), "--create", "--file", executable.toString(), "--manifest", mainManifest.toString(), "-C", classes.toString(), "."), "consumer-jar");
        for (String form : List.of("class", "module", "executable")) {
            var command = new ArrayList<>(List.of(javaHome.resolve("bin/java").toString()));
            if (form.equals("class")) command.addAll(List.of("-cp", jar + java.io.File.pathSeparator + classes, "Consumer"));
            else if (form.equals("module")) command.addAll(List.of("--module-path", jar.toString(), "--add-modules", manifest.getProperty("java.module"), "-cp", classes.toString(), "Consumer"));
            else command.addAll(List.of("-jar", executable.toString()));
            check(BridgeEntryTests.run(folder, command, "consumer-" + form).equals("producer-ok\n"), "unexpected consumer output");
        }
        for (String budget : List.of("0", "1", "2")) {
            var command = List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=" + budget, javaHome.resolve("bin/java").toString(), "-Xcheck:jni",
                    "-cp", jar + java.io.File.pathSeparator + classes, "Consumer", budget);
            check(BridgeEntryTests.run(folder, command, "consumer-budget-" + budget).equals("producer-ok\n"), "budget failure");
        }
    }

    private static String command(Path folder, String name, int expected, String[] arguments) throws Exception {
        var output = new ByteArrayOutputStream(); int status;
        try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) { status = Main.run(arguments, stream, stream); }
        String text = output.toString(StandardCharsets.UTF_8);
        Files.writeString(folder.resolve(name + ".command.txt"), String.join("\n", arguments) + "\n");
        Files.writeString(folder.resolve(name + ".log"), text); Files.writeString(folder.resolve(name + ".exit.txt"), status + "\n");
        check(status == expected, name + " returned " + status + ": " + text); return text;
    }

    private static final String CONSUMER = """
            public final class Consumer {
                public static void main(String[] args) throws Exception {
                    if (produced.Engine.add(20, 22) != 42 || !produced.Engine.fixed().equals("fixed")) throw new AssertionError("scalar/literal");
                    if (args.length != 0) {
                        for (int i = 0; i < 3; i++) {
                            try { produced.Engine.fresh("a", "b"); throw new AssertionError("conversion did not fail"); } catch (OutOfMemoryError expected) {}
                            if (produced.Engine.add(19, 23) != 42) throw new AssertionError("post-failure call");
                        }
                        if (produced.Engine.calls() != (args[0].equals("2") ? 1 : 0)) throw new AssertionError("target entry count");
                    } else {
                        for (String value : new String[]{null, "", "a\\0b", "" + (char)0xd800}) {
                            if (!java.util.Objects.equals(value, produced.Engine.echo(value))) throw new AssertionError("String alias");
                            if (value != null && !produced.Engine.fresh(value, "b").equals(value)) throw new AssertionError("fresh String");
                        }
                        for (int i = 0; i < 2; i++) {
                            try { produced.Engine.fail(); throw new AssertionError("checked failure absent"); }
                            catch (java.io.IOException expected) { if (!expected.getMessage().equals("producer failure") || expected.getStackTrace().length == 0) throw expected; }
                            try { produced.Engine.Lazy.read(); throw new AssertionError("initializer failure absent"); }
                            catch (IllegalStateException expected) { if (!expected.getMessage().equals("lazy failure")) throw expected; }
                        }
                        if (produced.Engine.add(1, 2) != 3) throw new AssertionError("continued call");
                    }
                    System.out.println("producer-ok");
                }
            }
            """;
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
