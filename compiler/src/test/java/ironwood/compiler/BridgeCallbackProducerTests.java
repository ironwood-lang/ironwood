// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

final class BridgeCallbackProducerTests {
    static final String NAME = "Java Bridge producer packages proved synchronous listeners with artifact parity";
    private BridgeCallbackProducerTests() {}

    static void producer() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p5/producer").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path source = directory.resolve("source/listening/Processor.iron");
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE);
        Path listener = source.resolveSibling("Listener.iron"), alternate = source.resolveSibling("Alternate.iron");
        Files.writeString(listener, "package listening; public interface Listener { long call(long value); }");
        Files.writeString(alternate, "package listening; public interface Alternate { boolean accept(long value); }");
        Path classes = directory.resolve("iron-classes"), archive = directory.resolve("input.ironjar");
        BridgeProducerTests.command(directory, "compile", 0, new String[]{"-d", classes.toString(), source.toString(), listener.toString(), alternate.toString()});
        IronJar.create(archive, List.of(classes), List.of());
        Properties reference = null;
        Path first = null;
        for (String variant : List.of("source", "classes", "archive")) {
            Path folder = directory.resolve(variant);
            Files.createDirectories(folder);
            Path jar = folder.resolve("listeners.jar");
            var args = new ArrayList<>(List.of("--java-bridge", "--export", "listening", "--unfreed=error",
                    "-o", jar.toString(), variant.equals("source") ? "-O0" : "-O3"));
            if (variant.equals("source")) args.addAll(List.of(source.toString(), listener.toString(), alternate.toString()));
            else args.addAll(List.of("--source-path", directory.resolve("absent").toString(), "-cp",
                    (variant.equals("classes") ? classes : archive).toString()));
            BridgeProducerTests.command(folder, "producer", 0, args.toArray(String[]::new));
            var manifest = inspect(jar);
            if (reference == null) { reference = manifest; first = jar; }
            else for (String key : List.of("generation", "api", "program")) {
                check(reference.getProperty(key).equals(manifest.getProperty(key)), "callback artifact parity: " + key);
            }
            consumer(folder, jar, manifest);
            if (variant.equals("source")) {
                Files.delete(source); Files.delete(listener); Files.delete(alternate);
            }
        }
        check(first != null, "missing paired callback jar");
        byte[] previous = Files.readAllBytes(first);
        Files.writeString(listener, "package listening; public interface Listener { long call(long value); }");
        Files.writeString(alternate, "package listening; public interface Alternate { boolean accept(long value); }");
        for (String mutation : List.of("static Listener retained; public static long bad(Listener l) { retained = l; return l.call(1L); }",
                "static long state; public static long bad(Listener l) { return state + l.call(1L); }",
                "public static Listener bad(Listener l) { l.call(1L); return l; }")) {
            Files.writeString(source, SOURCE.replace("private Processor() {}", "private Processor() {} " + mutation));
            for (var mode : UnfreedMode.values()) {
                BridgeProducerTests.command(directory, "reject-" + Math.abs(mutation.hashCode()) + "-" + mode, 1,
                        new String[]{"--java-bridge", "--export", "listening", "--unfreed=" + mode.name().toLowerCase(java.util.Locale.ROOT),
                                "-o", first.toString(), source.toString(), listener.toString(), alternate.toString()});
                check(java.util.Arrays.equals(previous, Files.readAllBytes(first)), "refused callback changed published output");
            }
        }
        Files.writeString(source, SOURCE);
        for (String declaration : List.of(
                "public interface Listener { long call(long value); Object reference(Object value); }",
                "public interface Listener { long call(long value); static long utility() { return 1L; } }",
                "public interface Listener { long call(long value); default long utility() { return 1L; } }",
                "public interface Listener<T> { long call(long value); }",
                "interface Parent { long call(long value); } public interface Listener extends Parent {}")) {
            Files.writeString(listener, "package listening; " + declaration);
            BridgeProducerTests.command(directory, "reject-interface-" + Math.abs(declaration.hashCode()), 1,
                    new String[]{"--java-bridge", "--export", "listening", "--unfreed=error", "-o", first.toString(),
                            source.toString(), listener.toString(), alternate.toString()});
            check(java.util.Arrays.equals(previous, Files.readAllBytes(first)), "unsupported listener changed published output");
        }
        System.out.println("callback producer evidence: " + directory);
    }

    private static Properties inspect(Path jar) throws Exception {
        var properties = new Properties();
        try (var zip = new ZipFile(jar.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) { properties.load(input); }
            check(properties.getProperty("projection").equals("callbacks-v1"), "missing callback projection identity");
            var map = new java.util.TreeMap<String, String>();
            properties.stringPropertyNames().forEach(key -> map.put(key, properties.getProperty(key)));
            check(BridgeGeneration.fromManifest(map).identity().equals(properties.getProperty("generation")), "manifest restoration mismatch");
            for (var entry : zip.stream().toList()) {
                if (entry.getName().equals(BridgePackageManifest.PATH)) continue;
                try (var input = zip.getInputStream(entry)) {
                    check(BridgeGeneration.bytesDigest(input.readAllBytes()).equals(properties.getProperty("content.sha256." + entry.getName())),
                            "unpaired callback output " + entry.getName());
                }
            }
            for (String name : List.of("listening/Listener.class", "listening/Alternate.class", "listening/Processor.class",
                    "META-INF/ironwood/java-sources/listening/Listener.java", "META-INF/ironwood/javadoc/listening/Listener.html")) {
                check(zip.getEntry(name) != null, "missing projected listener artifact " + name);
            }
        }
        return properties;
    }

    private static void consumer(Path folder, Path jar, Properties manifest) throws Exception {
        Path source = folder.resolve("Consumer.java"), classes = folder.resolve("consumer"), jdk = Path.of(System.getProperty("java.home"));
        Files.writeString(source, CONSUMER);
        BridgeEntryTests.run(folder, List.of(jdk.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                "-cp", jar.toString(), "-d", classes.toString(), source.toString()), "javac");
        for (String mode : List.of("class", "module", "allocation-0", "allocation-1")) {
            var args = new ArrayList<String>();
            if (mode.startsWith("allocation-")) args.addAll(List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=" + mode.substring(11)));
            args.addAll(List.of(jdk.resolve("bin/java").toString(), "-Xcheck:jni"));
            if (mode.equals("module")) args.addAll(List.of("--module-path", jar.toString(), "--add-modules", manifest.getProperty("java.module"),
                    "-cp", classes.toString(), "Consumer"));
            else args.addAll(List.of("-cp", jar + java.io.File.pathSeparator + classes, "Consumer"));
            if (mode.startsWith("allocation-")) args.add("allocation");
            check(BridgeEntryTests.run(folder, args, "consumer-" + mode).equals("callback-producer-ok\n"), "callback consumer failed: " + mode);
        }
    }

    private static final String SOURCE = """
            package listening;
            public final class Processor {
                private Processor() {}
                public static long run(Listener listener, long count) {
                    long sum = 0L;
                    for (long index = 0L; index < count; index++) sum += listener.call(index);
                    return sum;
                }
                public static long recover(Listener listener) {
                    try { return listener.call(1L); }
                    catch (RuntimeException failure) { return -99L; }
                }
                public static long pair(Listener listener, Alternate alternate, long value) {
                    return alternate.accept(value) ? listener.call(value) : 0L;
                }
                public static void consume(Listener listener) { listener.call(1L); }
                public static double real(Listener listener, double value) { listener.call(1L); return value; }
                public static long fault(Listener listener, long value) {
                    return listener.call(value) / (value - value);
                }
            }
            """;
    private static final String CONSUMER = """
            import listening.*;
            public final class Consumer {
                public static void main(String[] args) {
                    Listener identity = value -> value;
                    if (args.length != 0) {
                        try { Processor.pair(identity, value -> true, 4L); throw new AssertionError("allocation succeeded"); }
                        catch (OutOfMemoryError expected) { }
                    } else {
                        if (Processor.run(identity, 10L) != 45L || Processor.pair(identity, value -> value == 9L, 9L) != 9L)
                            throw new AssertionError("callback values");
                        if (Processor.run(value -> Processor.run(identity, value), 4L) != 4L) throw new AssertionError("nested invocation");
                        Processor.consume(identity);
                        if (1.0 / Processor.real(identity, -0.0) != Double.NEGATIVE_INFINITY) throw new AssertionError("real result");
                        RuntimeException original = new RuntimeException("original");
                        try { Processor.consume(value -> { throw original; }); throw new AssertionError("void failure"); }
                        catch (RuntimeException caught) { if (caught != original) throw new AssertionError("void identity"); }
                        for (int i = 0; i < 30; i++) {
                            try { Processor.run(value -> { throw original; }, 1L); throw new AssertionError("missing failure"); }
                            catch (RuntimeException caught) { if (caught != original) throw new AssertionError("identity"); }
                            if (Processor.recover(value -> { throw original; }) != -99L) throw new AssertionError("native catch");
                        }
                        try { Processor.run(null, 1L); throw new AssertionError("missing null failure"); }
                        catch (NullPointerException expected) { }
                        try { Processor.fault(identity, 7L); throw new AssertionError("missing arithmetic failure"); }
                        catch (ArithmeticException expected) { }
                    }
                    System.out.println("callback-producer-ok");
                }
            }
            """;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
