// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;
import ironwood.compiler.bridge.BridgeByteViewSources;

/** Child JVM validation of borrowed direct storage and ordinary value composition. */
final class BridgeByteViewProducerTests {
    static final String NAME = "Java Bridge byte-view producer preserves shared storage bounds identity and artifact parity";
    private BridgeByteViewProducerTests() {}
    static void producer() throws Exception {
        Path base = Path.of("workspace/java-bridge/byteviews/producer").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path source = directory.resolve("Values.iron"), reader = directory.resolve("Reader.iron");
        Files.writeString(source, BridgeByteViewTests.SOURCE.replace("private Values() {}", """
                private Values() {}
                public static long allocations() { return System.allocationCount(); }
                public static int many(ByteView a0, ByteView a1, ByteView a2, ByteView a3,
                        ByteView a4, ByteView a5, ByteView a6, ByteView a7, ByteView a8, ByteView a9,
                        ByteView a10, ByteView a11, ByteView a12, ByteView a13, ByteView a14, ByteView a15,
                        ByteView a16, ByteView a17, ByteView a18, ByteView a19, ByteView a20, ByteView a21,
                        ByteView a22, ByteView a23, ByteView a24, ByteView a25, ByteView a26, ByteView a27,
                        ByteView a28, ByteView a29, ByteView a30, ByteView a31) { return a0.get(0) + a31.get(0); }
                public static int at(ByteView view, int index) { return view.get(index); }
                public static int loop(ByteView view) {
                    for (int i = 0; i < view.length(); i++) view.put(i, (byte)i);
                    return view.length();
                }
                public static int overlap(ByteView first, ByteView second) {
                    first.put(1, (byte)7);
                    second.put(1, (byte)(second.get(0) + 1));
                    return first.get(2);
                }
                public static void fail(ByteView view) {
                    view.put(0, (byte)42);
                    throw new IllegalArgumentException("after write");
                }
                public static int mixed(ByteView view, byte[] array, String text) {
                    view.put(0, array[0]); array[1] = view.get(1);
                    return text.length();
                }
                public static String text(ByteView view) { return view.length() == 0 ? "empty" : "bytes"; }
                """));
        Files.writeString(reader, """
                package viewfixture;
                import ironwood.bridge.ByteView;
                public final class Reader {
                    private final int bias;
                    public Reader(int bias) { this.bias = bias; }
                    public long sum(ByteView view) { return bias + Values.sum(view); }
                }
                """);
        Path classes = directory.resolve("iron-classes"), archive = directory.resolve("views.ironjar");
        BridgeProducerTests.command(directory, "compile", 0, new String[]{"--unfreed=off", "-d", classes.toString(), source.toString(), reader.toString()});
        IronJar.create(archive, List.of(classes), List.of());
        Properties reference = null;
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (String variant : List.of("source", "classes", "archive")) {
            Path folder = directory.resolve(variant); Files.createDirectories(folder);
            Path jar = folder.resolve("views.jar"), values = folder.resolve(BridgeByteViewSources.JAR_NAME);
            var arguments = new ArrayList<>(List.of("--java-bridge", "--export", "viewfixture", "--unfreed=off",
                    variant.equals("source") ? "-O0" : "-O3", "-o", jar.toString()));
            if (variant.equals("source")) arguments.addAll(List.of(source.toString(), reader.toString()));
            else arguments.addAll(List.of("--source-path", directory.resolve("absent").toString(), "-cp",
                    (variant.equals("classes") ? classes : archive).toString()));
            BridgeProducerTests.command(folder, "producer", 0, arguments.toArray(String[]::new));
            var manifest = new Properties();
            try (var zip = new ZipFile(jar.toFile()); var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) {
                manifest.load(input);
                check(zip.getEntry(BridgeByteViewSources.CLASS_PATH) == null, "shared type duplicated in bridge jar");
                check(zip.getEntry(BridgeByteViewSources.RESOURCE) != null, "missing paired shared dependency");
            }
            if (reference == null) reference = manifest;
            else for (String key : List.of("generation", "api", "program")) {
                check(reference.getProperty(key).equals(manifest.getProperty(key)), "view artifact mismatch: " + key);
            }
            Path consumer = folder.resolve("ViewConsumer.java"), consumerClasses = folder.resolve("consumer-classes");
            Files.writeString(consumer, CONSUMER);
            String cp = jar + ":" + values;
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", cp, "-d", consumerClasses.toString(), consumer.toString()), "javac");
            for (String mode : List.of("normal", "gc", "oom")) {
                check(BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx32m",
                        "-XX:MaxDirectMemorySize=8m", "-cp", cp + ":" + consumerClasses, "ViewConsumer", mode), "consumer-" + mode)
                        .equals("views-ok:" + mode + "\n"), "view consumer failed: " + mode);
            }
            check(BridgeEntryTests.run(folder, List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=0",
                    javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", cp + ":" + consumerClasses,
                    "ViewConsumer", "native-oom"), "consumer-native-oom").equals("views-ok:native-oom\n"),
                    "failed native allocation lost immediate view write");
            if (variant.equals("source")) { Files.delete(source); Files.delete(reader); }
        }
        System.out.println("byte-view producer evidence: " + directory);
    }
    private static final String CONSUMER = """
            import ironwood.bridge.ByteView;
            import viewfixture.Values;
            import viewfixture.Reader;
            public final class ViewConsumer {
                private static volatile Object pressure;
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                private static void fails(Class<? extends Throwable> expected, Runnable code) {
                    try { code.run(); } catch (Throwable failure) {
                        if (expected.isInstance(failure)) return;
                        throw new AssertionError(failure);
                    }
                    throw new AssertionError("missing " + expected);
                }
                public static void main(String[] args) {
                    if (args[0].equals("native-oom")) {
                        ByteView view = ByteView.allocate(1);
                        fails(OutOfMemoryError.class, () -> Values.fail(view));
                        check(view.get(0) == 42 && Values.sum(view) == 42);
                        System.out.println("views-ok:native-oom"); return;
                    }
                    if (args[0].equals("oom")) {
                        fails(OutOfMemoryError.class, () -> ByteView.allocate(16 * 1024 * 1024));
                        check(Values.sum(ByteView.allocate(1)) == 0);
                        System.out.println("views-ok:oom"); return;
                    }
                    ByteView view = ByteView.allocate(4), slice = view.slice(1, 3), readOnly = slice.asReadOnly();
                    check(view.length() == 4 && !view.isReadOnly());
                    check(Values.many(view, view, view, view, view, view, view, view,
                            view, view, view, view, view, view, view, view,
                            view, view, view, view, view, view, view, view,
                            view, view, view, view, view, view, view, view) == 0);
                    check(Values.sum(view) == 0 && Values.sum(null) == -1);
                    check(Values.sum(ByteView.allocate(0)) == 0 && Values.sum(view.slice(4, 0)) == 0);
                    check(Values.same(view, view) && Values.same(null, null) && !Values.same(view, null));
                    check(!Values.same(view, view.slice(0, 4)) && !Values.same(slice, readOnly));
                    fails(IllegalArgumentException.class, () -> ByteView.allocate(-1));
                    for (int index : new int[]{-1, 4, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
                        fails(IndexOutOfBoundsException.class, () -> view.get(index));
                        fails(IndexOutOfBoundsException.class, () -> Values.at(view, index));
                        fails(IndexOutOfBoundsException.class, () -> Values.write(view, index, (byte)1, true));
                        fails(UnsupportedOperationException.class, () -> Values.write(readOnly, index, (byte)1, true));
                        fails(UnsupportedOperationException.class, () -> readOnly.put(index, (byte)1));
                    }
                    fails(IndexOutOfBoundsException.class, () -> slice.slice(2, Integer.MAX_VALUE));
                    fails(IndexOutOfBoundsException.class, () -> slice.slice(Integer.MAX_VALUE, 1));
                    fails(IndexOutOfBoundsException.class, () -> slice.slice(-1, 0));
                    fails(NullPointerException.class, () -> Values.write(null, 0, (byte)1, false));
                    check(Values.write(readOnly, -1, (byte)1, false) == 1);
                    check(Values.loop(ByteView.allocate(0).asReadOnly()) == 0);
                    fails(UnsupportedOperationException.class, () -> Values.loop(readOnly));
                    check(Values.overlap(view, slice) == 8 && readOnly.get(0) == 7 && readOnly.get(1) == 8);
                    Values.write(view, 0, (byte)-128, true);
                    check(Values.sum(view) == -113);
                    view.put(1, (byte)2);
                    fails(UnsupportedOperationException.class, () -> Values.overlap(view, readOnly));
                    check(view.get(1) == 7);
                    byte[] array = {(byte)-3, 0};
                    check(Values.mixed(view, array, "abc") == 3 && array[1] == 7 && view.get(0) == -3);
                    check(Values.text(view).equals("bytes") && Values.text(ByteView.allocate(0)).equals("empty"));
                    fails(IllegalArgumentException.class, () -> Values.fail(view));
                    check(view.get(0) == 42);
                    Reader reader = new Reader(9);
                    check(reader.sum(view) == Values.sum(view) + 9); reader.free();
                    long before = Values.allocations();
                    for (int i = 0; i < 20000; i++) {
                        Values.write(view, 0, (byte)i, true);
                        check(Values.sum(view) == (byte)i + 15);
                    }
                    check(Values.allocations() == before);
                    if (args[0].equals("gc")) {
                        ByteView survivor = ByteView.allocate(4096).slice(32, 2000).slice(8, 1000);
                        Thread collector = new Thread(() -> {
                            for (int i = 0; i < 80; i++) { pressure = new byte[262144]; System.gc(); }
                        });
                        collector.start();
                        for (int i = 0; i < 2000; i++) {
                            Values.write(survivor, 0, (byte)i, true);
                            check(Values.sum(survivor) == (byte)i);
                        }
                        try { collector.join(); } catch (InterruptedException failure) { throw new AssertionError(failure); }
                    }
                    System.out.println("views-ok:" + args[0]);
                }
            }
            """;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
