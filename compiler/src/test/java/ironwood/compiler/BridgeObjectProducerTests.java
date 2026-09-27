// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipFile;

final class BridgeObjectProducerTests {
    static final String NAME = "Java Bridge object producer preserves final proofs packaged parity and pending capability refusal";
    private BridgeObjectProducerTests() {}

    static void producer() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p3b/object-producer").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        var sources = new ArrayList<String>();
        var sourceTexts = Map.of("Mode.iron", BridgeEnumFacadeNativeTests.MODE, "Box.iron", BridgeEnumFacadeNativeTests.BOX,
                "Cases.iron", BridgeCustomExceptionTests.SOURCE.replace("private Cases() {}", """
                        private Cases() {}
                        private static Detail stored = new Detail("original");
                        public static void fail() throws Base { throw stored; }
                        public static long live() { return System.liveAllocationCount(); }
                        """));
        for (String name : sourceTexts.keySet().stream().sorted().toList()) {
            Path source = directory.resolve(name); Files.writeString(source, sourceTexts.get(name)); sources.add(source.toString());
        }
        Path classes = directory.resolve("iron-classes"), archive = directory.resolve("input.ironjar");
        var compilation = new ArrayList<>(List.of("--unfreed=off", "-d", classes.toString())); compilation.addAll(sources);
        command(directory, "compile-iron", 0, compilation); IronJar.create(archive, List.of(classes));
        Properties reference = null; Path firstJar = null;
        for (String variant : List.of("source", "classes", "archive")) {
            Path folder = directory.resolve(variant); Files.createDirectories(folder); Path jar = folder.resolve("objects.jar");
            var args = new ArrayList<>(List.of("--java-bridge", "--export", "enumjava", "--export", "customsnap", "--unfreed=off",
                    variant.equals("source") ? "-O0" : "-O3", "-o", jar.toString()));
            if (variant.equals("source")) args.addAll(sources); else args.addAll(List.of("-cp", (variant.equals("classes") ? classes : archive).toString()));
            command(folder, "producer", 0, args); var manifest = inspect(jar);
            if (reference == null) { reference = manifest; firstJar = jar; }
            else for (String key : List.of("generation", "api", "program", "java.module")) {
                check(reference.getProperty(key).equals(manifest.getProperty(key)), "object source/class/archive identity differs: " + key);
            }
            consumer(folder, jar, manifest, variant.equals("archive"));
        }
        check(firstJar != null, "missing object producer output"); byte[] previous = Files.readAllBytes(firstJar);
        Path rejected = directory.resolve("Rejected.iron");
        for (var mode : UnfreedMode.values()) {
            Files.writeString(rejected, "package pending; public final class Rejected { public Rejected() {} public int number() { return 1; } }");
            String output = command(directory, "roots-" + mode, 1, List.of("--java-bridge", "--export", "pending", "--unfreed=" + mode.name().toLowerCase(java.util.Locale.ROOT),
                    "-o", firstJar.toString(), rejected.toString()));
            check(output.contains("reclaimable roots await complete lifetime adapters"), output);
            for (var bad : Map.of("snapshot", "package pending; public final class Rejected extends Exception { public Object getObject() { return null; } }",
                    "array", "package pending; public final class Rejected { private Rejected() {} public static int[] get() { return null; } }").entrySet()) {
                Files.writeString(rejected, bad.getValue());
                command(directory, bad.getKey() + "-" + mode, 1, List.of("--java-bridge", "--export", "pending", "--unfreed=" + mode.name().toLowerCase(java.util.Locale.ROOT),
                        "-o", firstJar.toString(), rejected.toString()));
            }
            check(java.util.Arrays.equals(previous, Files.readAllBytes(firstJar)), "refused capability changed previous output");
        }
        System.out.println("object producer evidence: " + directory);
    }

    private static Properties inspect(Path jar) throws Exception {
        var properties = new Properties();
        try (var zip = new ZipFile(jar.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) { properties.load(input); }
            check(properties.getProperty("projection").equals("objects-v1") && properties.getProperty("java.supported").equals("21,22,23"), "object projection or Java baseline changed");
            check(properties.containsKey("native.input.object.adapters.sha256"), "object adapter identity absent");
            for (var entry : zip.stream().toList()) {
                if (entry.getName().equals(BridgePackageManifest.PATH)) continue;
                try (var input = zip.getInputStream(entry)) {
                    check(BridgeGeneration.bytesDigest(input.readAllBytes()).equals(properties.getProperty("content.sha256." + entry.getName())), "unpaired content: " + entry.getName());
                }
            }
            for (String path : List.of("META-INF/ironwood/java-sources/enumjava/Box.java", "META-INF/ironwood/java-sources/customsnap/Cases.java",
                    "META-INF/ironwood/javadoc/enumjava/Mode.html", "META-INF/ironwood/javadoc/customsnap/Cases.Detail.html",
                    "META-INF/ironwood/javadoc/legal/LICENSE", "META-INF/ironwood/licenses/LICENSE-MIT",
                    "META-INF/ironwood/licenses/SOURCE_PROVENANCE.md", "META-INF/ironwood/source/runtime/include/ironwood_bridge.h")) {
                check(zip.getEntry(path) != null, "missing object source/documentation/license: " + path);
            }
            check(properties.stringPropertyNames().stream().anyMatch(key -> key.startsWith("java.facade.registration.")), "private facade helper absent from pairing");
        }
        return properties;
    }

    private static void consumer(Path folder, Path jar, Properties manifest, boolean versions) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home")), source = folder.resolve("Consumer.java"), classes = folder.resolve("consumer-classes");
        Files.writeString(source, CONSUMER);
        BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                "-cp", jar.toString(), "-d", classes.toString(), source.toString()), "consumer-javac");
        Path executable = folder.resolve("consumer.jar"), mainManifest = folder.resolve("consumer.mf");
        Files.writeString(mainManifest, "Manifest-Version: 1.0\nMain-Class: Consumer\nClass-Path: objects.jar\n\n");
        BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/jar").toString(), "--create", "--file", executable.toString(), "--manifest",
                mainManifest.toString(), "-C", classes.toString(), "."), "consumer-jar");
        for (String form : List.of("class", "module", "executable")) {
            var command = new ArrayList<>(List.of(javaHome.resolve("bin/java").toString()));
            if (form.equals("class")) command.addAll(List.of("-cp", jar + System.getProperty("path.separator") + classes, "Consumer"));
            else if (form.equals("module")) command.addAll(List.of("--module-path", jar.toString(), "--add-modules", manifest.getProperty("java.module"), "-cp", classes.toString(), "Consumer"));
            else command.addAll(List.of("-jar", executable.toString()));
            check(BridgeEntryTests.run(folder, command, "consumer-" + form).equals("object-producer-ok\n"), "object consumer launch failed");
        }
        if (versions) for (String version : List.of("22", "23", "24")) {
            String release = switch (version) { case "22" -> "22.0.2+9"; case "23" -> "23.0.2+7"; default -> "24.0.2+12"; };
            Path launcher = Path.of("workspace/java-bridge/jdks/temurin-" + version + "-macos-arm64/jdk-" + release + "/Contents/Home/bin/java").toAbsolutePath();
            check(Files.isExecutable(launcher), "missing pinned launcher " + launcher);
            Path temporary = folder.resolve("tmp-" + version); Files.createDirectories(temporary);
            var command = new ArrayList<>(List.of(launcher.toString(), "-Xcheck:jni", "-Djava.io.tmpdir=" + temporary,
                    "-cp", jar + System.getProperty("path.separator") + classes, "Consumer"));
            if (version.equals("24")) command.add("version");
            check(BridgeEntryTests.run(folder, command, "consumer-java" + version).equals("object-producer-ok\n"), "object version check failed");
            if (version.equals("24")) try (var files = Files.list(temporary)) { check(files.findAny().isEmpty(), "Java 24 refusal extracted native payload"); }
        }
    }

    private static String command(Path folder, String name, int expected, List<String> args) throws Exception {
        return BridgeProducerTests.command(folder, name, expected, args.toArray(String[]::new));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final String CONSUMER = """
            import enumjava.Box;
            import enumjava.Mode;
            import customsnap.Cases;
            public final class Consumer {
                public static void main(String[] args) throws Exception {
                    check(Mode.SELL.ordinal() == 0 && Mode.BUY.name().equals("BUY"));
                    if (args.length != 0) {
                        try { new Box(Mode.SELL); throw new AssertionError("version guard absent"); }
                        catch (LinkageError expected) { check(expected.getMessage().contains("21-23")); }
                        System.out.println("object-producer-ok"); return;
                    }
                    Box box = new Box(Mode.SELL);
                    check(box.publish() == box && Box.recall() == box && box.self() == box && box.mode() == Mode.SELL);
                    check(box.select(Mode.BUY) == Mode.BUY && box.select(null) == null && Mode.SELL.code() == 41 && Mode.SELL.toString().equals("sold"));
                    long live = Cases.live();
                    Cases.Base saved;
                    try { Cases.fail(); throw new AssertionError("custom failure absent"); }
                    catch (Cases.Base expected) { saved = expected; check(expected.getCode() == 29 && expected.getMessage().equals("detail")); }
                    check(((Cases.Detail) saved).getCopy().equals("copy") && Cases.live() == live);
                    Thread reader = new Thread(() -> check(saved.getCode() == 29 && ((Cases.Detail) saved).getCopy().equals("copy")));
                    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>(); reader.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
                    reader.start(); reader.join(); check(failure.get() == null);
                    check(Box.recall() == box && Cases.ping() == 42);
                    System.out.println("object-producer-ok");
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
