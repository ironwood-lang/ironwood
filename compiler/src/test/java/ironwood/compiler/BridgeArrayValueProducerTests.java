// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

/** Production artifacts exercise copy-back, array identity and fresh-result ownership. */
final class BridgeArrayValueProducerTests {
    static final String NAME = "Java Bridge mutable array producer preserves writes aliases and fresh results";
    private BridgeArrayValueProducerTests() {}

    static void producer() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p7b/array-values").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path source = directory.resolve("Values.iron"), reader = directory.resolve("Reader.iron");
        Files.writeString(source, SOURCE);
        Files.writeString(reader, """
                package arrayvalues;
                public final class Reader {
                    private final int bias;
                    public Reader(int bias) { this.bias = bias; }
                    public int[] mutate(int[] values) { values[0] += bias; return values; }
                    public int[] fresh(int size) { return Values.fresh(size); }
                    public String text(int[] first, String value, int[] second) { first[0]++; second[0]++; return value; }
                    public Reader identity(int[] values) { values[0]++; return this; }
                    public Reader child(int[] values) { values[0]++; return new Reader(this.bias); }
                    public static final class Holder {
                        private Reader held;
                        public Holder() {}
                        public void retain(Reader reader, int[] values, boolean fail) {
                            this.held = reader; values[0]++;
                            if (fail) values[values.length] = 0;
                        }
                        public void clear() { this.held = null; }
                    }
                }
                """);
        Path classes = directory.resolve("iron-classes"), archive = directory.resolve("values.ironjar");
        BridgeProducerTests.command(directory, "compile", 0, new String[]{"--unfreed=off", "-d", classes.toString(), source.toString(), reader.toString()});
        IronJar.create(archive, List.of(classes), List.of());
        Properties reference = null;
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (String variant : List.of("source", "classes", "archive")) {
            Path folder = directory.resolve(variant); Files.createDirectories(folder);
            Path jar = folder.resolve("values.jar");
            var arguments = new ArrayList<>(List.of("--java-bridge", "--export", "arrayvalues", "--unfreed=off",
                    variant.equals("source") ? "-O0" : "-O3", "-o", jar.toString()));
            if (variant.equals("source")) arguments.addAll(List.of(source.toString(), reader.toString()));
            else arguments.addAll(List.of("--source-path", directory.resolve("absent").toString(), "-cp",
                    (variant.equals("classes") ? classes : archive).toString()));
            BridgeProducerTests.command(folder, "producer", 0, arguments.toArray(String[]::new));
            var manifest = new Properties();
            try (var zip = new ZipFile(jar.toFile()); var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) { manifest.load(input); }
            if (reference == null) reference = manifest;
            else for (String key : List.of("generation", "api", "program")) {
                check(reference.getProperty(key).equals(manifest.getProperty(key)), "array value identity mismatch: " + key);
            }
            Path consumer = folder.resolve("ArrayValueConsumer.java"), consumerClasses = folder.resolve("consumer-classes");
            Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", consumerClasses.toString(), consumer.toString()), "javac");
            for (String budget : List.of("normal", "0", "1", "2")) {
                var command = new ArrayList<String>();
                if (!budget.equals("normal")) command.addAll(List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=" + budget));
                command.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", jar + ":" + consumerClasses,
                        "ArrayValueConsumer", budget));
                check(BridgeEntryTests.run(folder, command, "consumer-" + budget).equals("array-values-ok:" + budget + "\n"), "array value consumer failed");
            }
            if (variant.equals("source")) { Files.delete(source); Files.delete(reader); }
        }
        System.out.println("array value producer evidence: " + directory);
    }

    static final String SOURCE = """
            package arrayvalues;
            public final class Values {
                private Values() {}
                private static int entered;
                public static long live() { return System.liveAllocationCount(); }
                public static long allocations() { return System.allocationCount(); }
                public static int calls() { return entered; }
                public static int[] two(int[] first, int[] second) {
                    entered++; first[0] += 10; second[0] += 20; return first;
                }
                public static int[] choose(int[] first, int[] second, boolean left) { return left ? first : second; }
                public static int[] absent() { return null; }
                public static int[] fresh(int size) {
                    int[] result = new int[size];
                    for (int i = 0; i < size; i++) result[i] = i * 3;
                    return result;
                }
                public static int[] result(int[] values) { values[0]++; return fresh(4); }
                public static void fail(int[] first, int[] second) {
                    first[0] = 11; second[0] = 22; first[first.length] = 33;
                }
                public static boolean[] bool(boolean[] a, boolean v) { a[0] = v; return a; }
                public static byte[] b(byte[] a, byte v) { a[0] = v; return a; }
                public static short[] s(short[] a, short v) { a[0] = v; return a; }
                public static char[] c(char[] a, char v) { a[0] = v; return a; }
                public static long[] l(long[] a, long v) { a[0] = v; return a; }
                public static float[] f(float[] a, float v) { a[0] = v; return a; }
                public static double[] d(double[] a, double v) { a[0] = v; return a; }
                public static boolean[] newBool(boolean v) { return new boolean[]{v}; }
                public static byte[] newB(byte v) { return new byte[]{v}; }
                public static short[] newS(short v) { return new short[]{v}; }
                public static char[] newC(char v) { return new char[]{v}; }
                public static long[] newL(long v) { return new long[]{v}; }
                public static float[] newF(float v) { return new float[]{v}; }
                public static double[] newD(double v) { return new double[]{v}; }
            }
            """;

    private static final String CONSUMER = """
            import arrayvalues.Values;
            import arrayvalues.Reader;
            public final class ArrayValueConsumer {
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                public static void main(String[] args) {
                    long baseline = Values.live();
                    int[] first = {1}, second = {2};
                    if (!args[0].equals("normal")) {
                        int budget = Integer.parseInt(args[0]);
                        try {
                            check(Values.two(first, second) == first);
                            check(budget == 2 && Values.calls() == 1 && first[0] == 11 && second[0] == 22);
                        } catch (OutOfMemoryError expected) {
                            check(budget < 2 && Values.calls() == 0 && first[0] == 1 && second[0] == 2);
                        }
                        check(Values.live() == baseline);
                        System.out.println("array-values-ok:" + args[0]); return;
                    }
                    check(Values.choose(first, second, true) == first && Values.choose(first, second, false) == second);
                    check(Values.choose(first, null, false) == null && Values.absent() == null);
                    int[] empty = new int[0]; check(Values.choose(empty, first, true) == empty);
                    long before = Values.allocations();
                    check(Values.two(first, first) == first && first[0] == 31);
                    check(Values.allocations() == before + 1);
                    check(Values.two(first, second) == first && first[0] == 41 && second[0] == 22);
                    Reader reader = new Reader(7);
                    check(reader.mutate(first) == first && first[0] == 48);
                    check(reader.fresh(0).length == 0);
                    int[] textValues = {0};
                    check(reader.text(textValues, "payload", textValues).equals("payload") && textValues[0] == 2);
                    check(reader.text(textValues, null, textValues) == null && textValues[0] == 4);
                    check(reader.identity(textValues) == reader && textValues[0] == 5);
                    Reader child = reader.child(textValues);
                    check(child != reader && textValues[0] == 6); child.free();
                    Reader.Holder holder = new Reader.Holder();
                    holder.retain(reader, textValues, false);
                    check(textValues[0] == 7);
                    try { reader.free(); throw new AssertionError(); } catch (IllegalStateException expected) { }
                    holder.clear(); holder.free();
                    reader.free();
                    int[] result = Values.result(first); check(first[0] == 49 && result.length == 4 && result[3] == 9);
                    check(Values.fresh(0).length == 0 && Values.fresh(4) != Values.fresh(4));
                    int[] large = Values.fresh(1048576); check(large[1048575] == 1048575 * 3);
                    boolean[] bool = {false}; check(Values.bool(bool, true) == bool && bool[0] && Values.newBool(true)[0]);
                    byte[] b = {0}; check(Values.b(b, (byte)-128) == b && b[0] == -128 && Values.newB((byte)-128)[0] == -128);
                    short[] s = {0}; check(Values.s(s, (short)-32768) == s && s[0] == -32768 && Values.newS((short)-32768)[0] == -32768);
                    char[] c = {0}; check(Values.c(c, '\\ud800') == c && c[0] == '\\ud800' && Values.newC('\\uffff')[0] == '\\uffff');
                    long[] l = {0}; check(Values.l(l, Long.MIN_VALUE) == l && l[0] == Long.MIN_VALUE && Values.newL(Long.MIN_VALUE)[0] == Long.MIN_VALUE);
                    float[] f = {0}; check(Values.f(f, -0.0f) == f && Float.floatToRawIntBits(f[0]) == 0x80000000);
                    check(Float.floatToRawIntBits(Values.newF(-0.0f)[0]) == 0x80000000 && Float.isNaN(Values.newF(Float.NaN)[0]));
                    double[] d = {0}; check(Values.d(d, -0.0) == d && Double.doubleToRawLongBits(d[0]) == Long.MIN_VALUE);
                    check(Double.doubleToRawLongBits(Values.newD(-0.0)[0]) == Long.MIN_VALUE && Double.isNaN(Values.newD(Double.NaN)[0]));
                    for (int i = 0; i < 10000; i++) { check(Values.result(first)[3] == 9); check(Values.choose(first, first, true) == first); }
                    check(Values.live() == baseline);
                    Reader retained = new Reader(3); Reader.Holder failedHolder = new Reader.Holder();
                    try { failedHolder.retain(retained, textValues, true); throw new AssertionError(); }
                    catch (ArrayIndexOutOfBoundsException expected) { check(textValues[0] == 8); }
                    try { retained.free(); throw new AssertionError(); } catch (IllegalStateException expected) { }
                    failedHolder.clear(); failedHolder.free(); retained.free();
                    try { Values.fail(first, second); throw new AssertionError(); }
                    catch (ArrayIndexOutOfBoundsException expected) { check(first[0] == 11 && second[0] == 22); }
                    System.out.println("array-values-ok:normal");
                }
            }
            """;

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
