// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

/** Real generated jars and child JVMs, including partial native acquisition. */
final class BridgeArrayProducerTests {
    static final String NAME = "Java Bridge primitive array producer preserves values aliases and acquisition cleanup";
    private BridgeArrayProducerTests() {}

    static void producer() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p7b/arrays").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path source = directory.resolve("Values.iron");
        Files.writeString(source, BridgeArrayInputTests.SOURCE.replace("private Values() {}", """
                private Values() {}
                private static int entered;
                public static long live() { return System.liveAllocationCount(); }
                public static long allocations() { return System.allocationCount(); }
                public static int calls() { return entered; }
                public static boolean two(int[] first, int[] second) { entered++; return first == second; }
                """));
        Path reader = directory.resolve("Reader.iron");
        Files.writeString(reader, """
                package arrayfixture;
                public final class Reader {
                    private final int bias;
                    public Reader(int bias) { this.bias = bias; }
                    public long sum(int[] values) { return bias + Values.sum(values); }
                    public boolean same(int[] first, int[] second) { return first == second; }
                }
                """);
        Path classes = directory.resolve("iron-classes"), archive = directory.resolve("arrays.ironjar");
        BridgeProducerTests.command(directory, "compile", 0, new String[]{"--unfreed=off", "-d", classes.toString(), source.toString(), reader.toString()});
        IronJar.create(archive, List.of(classes), List.of());
        Properties reference = null;
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (String variant : List.of("source", "classes", "archive")) {
            Path folder = directory.resolve(variant); Files.createDirectories(folder);
            Path jar = folder.resolve("arrays.jar");
            var arguments = new ArrayList<>(List.of("--java-bridge", "--export", "arrayfixture", "--unfreed=off",
                    variant.equals("source") ? "-O0" : "-O3", "-o", jar.toString()));
            if (variant.equals("source")) arguments.addAll(List.of(source.toString(), reader.toString()));
            else arguments.addAll(List.of("--source-path", directory.resolve("absent").toString(), "-cp",
                    (variant.equals("classes") ? classes : archive).toString()));
            BridgeProducerTests.command(folder, "producer", 0, arguments.toArray(String[]::new));
            var manifest = new Properties();
            try (var zip = new ZipFile(jar.toFile()); var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) {
                manifest.load(input);
            }
            if (reference == null) reference = manifest;
            else for (String key : List.of("generation", "api", "program")) {
                check(reference.getProperty(key).equals(manifest.getProperty(key)), "array artifact mismatch: " + key);
            }
            Path consumer = folder.resolve("ArrayConsumer.java"), consumerClasses = folder.resolve("consumer-classes");
            Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", consumerClasses.toString(), consumer.toString()), "javac");
            for (String budget : List.of("normal", "0", "1", "2")) {
                var command = new ArrayList<String>();
                if (!budget.equals("normal")) command.addAll(List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=" + budget));
                command.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", jar + ":" + consumerClasses,
                        "ArrayConsumer", budget));
                check(BridgeEntryTests.run(folder, command, "consumer-" + budget).equals("arrays-ok:" + budget + "\n"),
                        "array consumer failed: " + budget);
            }
            if (variant.equals("source")) { Files.delete(source); Files.delete(reader); }
        }
        System.out.println("array producer evidence: " + directory);
    }

    private static final String CONSUMER = """
            import arrayfixture.Values;
            import arrayfixture.Reader;
            public final class ArrayConsumer {
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                public static void main(String[] args) {
                    long baseline = Values.live();
                    if (!args[0].equals("normal")) {
                        int budget = Integer.parseInt(args[0]);
                        try {
                            check(!Values.two(new int[]{1}, new int[]{1}));
                            check(budget == 2 && Values.calls() == 1);
                        } catch (OutOfMemoryError expected) { check(budget < 2 && Values.calls() == 0); }
                        check(Values.live() == baseline);
                        System.out.println("arrays-ok:" + args[0]); return;
                    }
                    int[] values = {1, -2, 3};
                    Reader reader = new Reader(7);
                    check(reader.sum(values) == 9 && reader.same(values, values));
                    reader.free();
                    long before = Values.allocations();
                    check(Values.same(values, values));
                    check(Values.allocations() == before + 1);
                    before = Values.allocations();
                    check(!Values.same(values, values.clone()));
                    check(Values.allocations() == before + 2);
                    check(Values.same(null, null) && !Values.same(null, values));
                    check(Values.sum(null) == -1 && Values.sum(new int[0]) == 0 && Values.sum(values) == 2);
                    check(Values.bool(new boolean[]{true}) && !Values.bool(new boolean[]{false}));
                    check(Values.b(new byte[]{-128}) == -128 && Values.s(new short[]{-32768}) == -32768);
                    check(Values.c(new char[]{'\\ud800'}) == '\\ud800' && Values.c(new char[]{'\\uffff'}) == '\\uffff');
                    check(Values.l(new long[]{Long.MIN_VALUE}) == Long.MIN_VALUE);
                    check(Float.floatToRawIntBits(Values.f(new float[]{-0.0f})) == 0x80000000);
                    check(Double.doubleToRawLongBits(Values.d(new double[]{-0.0})) == Long.MIN_VALUE);
                    check(Float.isNaN(Values.f(new float[]{Float.intBitsToFloat(0x7fc12345)})));
                    check(Double.isNaN(Values.d(new double[]{Double.longBitsToDouble(0x7ff8123456789abcL)})));
                    int[] large = new int[1048576]; java.util.Arrays.fill(large, 17);
                    check(Values.sum(large) == 17L * large.length);
                    for (int index = 0; index < 10000; index++) check(Values.same(values, values) && Values.sum(values) == 2);
                    check(Values.live() == baseline);
                    try { Values.b(new byte[0]); throw new AssertionError(); } catch (ArrayIndexOutOfBoundsException expected) {}
                    try { Values.b(null); throw new AssertionError(); } catch (NullPointerException expected) {}
                    System.out.println("arrays-ok:normal");
                }
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
