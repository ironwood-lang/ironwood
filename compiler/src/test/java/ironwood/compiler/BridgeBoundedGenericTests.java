// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Final bounds cover every Java-valid instantiation before admitting inputs. */
final class BridgeBoundedGenericTests {
    static final String NAME = "Java Bridge bounded generic inputs preserve construction and retention proofs";
    static final String PRODUCER = "Java Bridge bounded generic producer preserves erased clients and failure cleanup";
    private BridgeBoundedGenericTests() {}

    static final String BOX = """
            package bounded;
            public final class Box<T extends Quote> {
                private T value;
                public Box(T value) { this.value = value; }
                public Box(T value, boolean fail) { this.value = value; if (fail) throw new IllegalArgumentException("construction"); }
                public void set(T value) { this.value = value; }
                public void set(T value, boolean before, boolean after) {
                    if (before) throw new IllegalArgumentException("before");
                    this.value = value;
                    if (after) throw new IllegalArgumentException("after");
                }
                public T get() { return value; }
                public Box<T> self() { return this; }
                public void copy(Box<T> other) { value = other.value; }
                public static long live() { return System.liveAllocationCount(); }
                public static long allocations() { return System.allocationCount(); }
            }
            """;
    static List<SourceFile> sources(String box) {
        return List.of(SourceFile.of("Box.iron", box),
                SourceFile.of("Quote.iron", "package bounded; public final class Quote { public int value() { return 17; } }"),
                SourceFile.of("Other.iron", "package bounded; public final class Other {}"),
                SourceFile.of("Pair.iron", """
                        package bounded;
                        public final class Pair<T extends Quote, U extends Other> {
                            private T first;
                            private U second;
                            private static Object published;
                            public Pair(T first, U second) { this.first = first; this.second = second; }
                            public T first() { return first; }
                            public U second() { return second; }
                            public void set(T first, U second) { this.first = first; this.second = second; }
                            public void publish() { published = this; }
                        }
                        """),
                SourceFile.of("Value.iron", "package bounded; public final class Value { public int value() { return 29; } }"),
                SourceFile.of("Sink.iron", """
                        package bounded;
                        public final class Sink<T extends Value> {
                            private T value;
                            public Sink(T value) { this.value = value; }
                            public Sink(T value, boolean fail) { this.value = value; if (fail) throw new IllegalArgumentException("construction"); }
                            public void set(T value) { this.value = value; }
                            public void set(T value, boolean before, boolean after) {
                                if (before) throw new IllegalArgumentException("before");
                                this.value = value;
                                if (after) throw new IllegalArgumentException("after");
                            }
                            public T echo(T value) { return value; }
                            public int read() { return value == null ? 0 : value.value(); }
                        }
                        """),
                SourceFile.of("Leaf.iron", "package bounded; public final class Leaf { Leaf() {} public int value() { return 31; } }"),
                SourceFile.of("Owner.iron", """
                        package bounded;
                        public final class Owner {
                            private final Leaf leaf = new Leaf();
                            public Owner() {}
                            destructor { free leaf; }
                            public Leaf view() { return leaf; }
                        }
                        """),
                SourceFile.of("Keeper.iron", """
                        package bounded;
                        public final class Keeper<T extends Leaf> {
                            private T value;
                            public Keeper(T value) { this.value = value; }
                            public void set(T value) { this.value = value; }
                            public int read() { return value == null ? 0 : value.value(); }
                        }
                        """));
    }

    static void proofs() {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(sources(BOX));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("bounded"));
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        var admission = proof.contract().orElseThrow();
        var generation = BridgeGeneration.createObjects("bounded", artifact, admission, "test", "a".repeat(64), "b".repeat(64));
        var java = BridgePermanentJavaSources.generateRoots(artifact, admission, generation);
        BridgePermanentNativeSources.generateRoots(artifact, admission, generation, java);
        String box = java.declarations().sources().get("bounded/Box.java");
        check(box.contains("class Box<T extends bounded.Quote>") && box.contains("public Box(T value)")
                && box.contains("public void set(T value)"), "bounded source signature lost");
        for (String rejected : List.of(BOX.replace("T extends Quote", "T"),
                BOX.replace("T extends Quote", "T extends Quote, U").replace("Box<T>", "Box<T, U>"),
                BOX.replace("public T get()", "public <U> T get()"),
                BOX.replace("public T get() { return value; }", "public T[] get() { return null; }"),
                BOX.replace("this.value = value; }", "this.value = value; long unknown = System.nanoTime(); }"))) {
            var bad = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(sources(rejected));
            check(bad.valid(), bad.diagnostics().toString());
            check(BridgeObjectAdmission.prove(bad, List.of("bounded")).status() != BridgeProof.Status.PROVED,
                    "unsupported bounded surface admitted: " + rejected);
        }
        for (var mode : UnfreedMode.values()) for (String invalid : List.of("free this;", "free value;")) {
            var bad = new CompilerPipeline(mode).analyzeForBridge(sources(BOX.replace("public T get() { return value; }",
                    "public T get() { " + invalid + " return value; }")));
            check(!bad.valid(), "bounded generic free bypassed source safety in " + mode);
        }
        for (boolean cycle : List.of(false, true)) {
            var badSources = sources(BOX).stream().map(source -> {
                String text = source.content();
                if (cycle && source.path().toString().equals("Value.iron")) text = text.replace("public int value()",
                        "private Sink<Value> sink; public void retain(Sink<Value> sink) { this.sink = sink; } public int value()");
                if (!cycle && source.path().toString().equals("Quote.iron")) text = text.replace("final class", "class");
                return SourceFile.of(source.path().toString(), text);
            }).toList();
            var bad = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(badSources);
            check(bad.valid(), bad.diagnostics().toString());
            var rejected = BridgeObjectAdmission.prove(bad, List.of("bounded"));
            check(rejected.status() != BridgeProof.Status.PROVED, "cycle or nonfinal bound admitted");
            if (cycle) check(rejected.reason().contains("cycle"), rejected.reason());
        }
    }

    static void producer() throws Exception {
        Path base = Path.of("workspace/java-bridge/bounded-generics/producer").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        var inputs = new ArrayList<String>();
        for (var source : sources(BOX)) {
            Path file = directory.resolve(source.path().getFileName());
            Files.writeString(file, source.content()); inputs.add(file.toString());
        }
        Path classes = directory.resolve("iron-classes"), archive = directory.resolve("bounded.ironjar");
        var compile = new ArrayList<>(List.of("--unfreed=off", "-d", classes.toString())); compile.addAll(inputs);
        BridgeProducerTests.command(directory, "compile", 0, compile.toArray(String[]::new));
        IronJar.create(archive, List.of(classes), List.of());
        Properties reference = null;
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (String variant : List.of("source", "classes", "individual", "archive")) {
            Path folder = directory.resolve(variant); Files.createDirectories(folder);
            Path jar = folder.resolve("bounded.jar");
            var args = new ArrayList<>(List.of("--java-bridge", "--export", "bounded", "--unfreed=off",
                    variant.equals("source") ? "-O0" : "-O3", "-o", jar.toString()));
            if (variant.equals("source")) args.addAll(inputs);
            else {
                String cp = (variant.equals("archive") ? archive : classes).toString();
                if (variant.equals("individual")) try (var files = Files.walk(classes)) {
                    cp = files.filter(path -> path.toString().endsWith(IronClass.EXTENSION)).sorted()
                            .map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
                }
                args.addAll(List.of("--source-path", directory.resolve("absent").toString(), "-cp", cp));
            }
            BridgeProducerTests.command(folder, "producer", 0, args.toArray(String[]::new));
            var manifest = new Properties();
            try (var zip = new ZipFile(jar.toFile()); var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) { manifest.load(input); }
            if (reference == null) reference = manifest;
            else for (String key : List.of("generation", "api", "program")) {
                check(reference.getProperty(key).equals(manifest.getProperty(key)), "bounded artifact mismatch: " + key);
            }
            Path consumer = folder.resolve("BoundedConsumer.java"), output = folder.resolve("consumer-classes");
            Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", output.toString(), consumer.toString()), "javac");
            check(BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni",
                    "-cp", jar + ":" + output, "BoundedConsumer"), "consumer").equals("bounded-generics-ok\n"), "bounded consumer failed");
            check(BridgeEntryTests.run(folder, List.of("env", "IRONWOOD_ALLOCATION_LIMIT=48",
                    javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", jar + ":" + output,
                    "BoundedConsumer", "oom"), "consumer-oom").equals("bounded-oom-ok\n"), "bounded OOM containment failed");
        }
        Path rejected = directory.resolve("rejected"); Files.createDirectories(rejected);
        var badInputs = new ArrayList<String>();
        for (var source : sources(BOX.replace("T extends Quote", "T"))) {
            Path file = rejected.resolve(source.path().getFileName());
            Files.writeString(file, source.content()); badInputs.add(file.toString());
        }
        Path badClasses = rejected.resolve("classes"), badArchive = rejected.resolve("bad.ironjar");
        var badCompile = new ArrayList<>(List.of("--unfreed=off", "-d", badClasses.toString())); badCompile.addAll(badInputs);
        BridgeProducerTests.command(rejected, "compile", 0, badCompile.toArray(String[]::new));
        IronJar.create(badArchive, List.of(badClasses), List.of());
        Path preserved = rejected.resolve("preserved.jar"); Files.writeString(preserved, "existing output");
        for (String variant : List.of("source", "classes", "archive")) {
            var args = new ArrayList<>(List.of("--java-bridge", "--export", "bounded", "--unfreed=off", "-o", preserved.toString()));
            if (variant.equals("source")) args.addAll(badInputs);
            else args.addAll(List.of("--source-path", directory.resolve("absent").toString(), "-cp",
                    (variant.equals("classes") ? badClasses : badArchive).toString()));
            BridgeProducerTests.command(rejected, "producer-" + variant, 1, args.toArray(String[]::new));
            check(Files.readString(preserved).equals("existing output"), "rejection changed existing output");
        }
        System.out.println("bounded generic evidence: " + directory);
    }

    static final String CONSUMER = """
            import bounded.*;
            public final class BoundedConsumer {
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                private static void retained(Runnable action) {
                    try { action.run(); throw new AssertionError("retained root freed"); }
                    catch (IllegalStateException expected) { }
                }
                private static void failed(Runnable action) {
                    try { action.run(); throw new AssertionError("missing native exception"); }
                    catch (IllegalArgumentException expected) { }
                }
                @SuppressWarnings({"rawtypes", "unchecked"})
                public static void main(String[] args) throws Exception {
                    if (args.length != 0) { oom(); return; }
                    Quote first = new Quote(), second = new Quote();
                    Other value = new Other(); Pair<Quote, Other> pair = new Pair<>(first, value);
                    pair.publish(); check(pair.first() == first && pair.second() == value);
                    Pair<? super Quote, ? super Other> pairView = pair; pairView.set(null, null);
                    check(pair.first() == null && pair.second() == null);
                    for (var method : Pair.class.getMethods()) check(!method.getName().equals("free"));
                    Box<Quote> box = new Box<>(first), other = new Box<>(second);
                    check(box.get() == first && box.self() == box && box.equals(box) && !box.equals(other));
                    check(box.hashCode() == box.hashCode() && box.toString().startsWith("bounded.Box@"));
                    Box raw = box; raw.set(second); check(raw.get() == second);
                    Box<? extends Quote> upper = box; upper.set(null); check(upper.get() == null);
                    Box<? super Quote> lower = box; lower.set(first); check(lower.get() == first);
                    box.copy(other); check(box.get() == second);
                    try { box.copy(null); throw new AssertionError(); } catch (NullPointerException expected) { }
                    check(box.get() == second);
                    failed(() -> box.set(first, true, false)); check(box.get() == second);
                    failed(() -> box.set(first, false, true)); check(box.get() == first);
                    failed(() -> new Box<>(first, true));
                    check(Box.class.getTypeParameters()[0].getBounds()[0] == Quote.class);
                    Value a = new Value(), b = new Value();
                    Sink<Value> sink = new Sink<>(a);
                    try { Quote wrong = (Quote) (Object) a; box.set(wrong); throw new AssertionError(); }
                    catch (ClassCastException expected) { }
                    try { Box.class.getMethod("set", Quote.class).invoke(box, a); throw new AssertionError(); }
                    catch (IllegalArgumentException expected) { }
                    check(sink.echo(a) == a && sink.echo(null) == null && sink.read() == 29);
                    retained(a::free);
                    failed(() -> sink.set(b, true, false)); retained(a::free);
                    failed(() -> sink.set(b, false, true)); a.free(); retained(b::free);
                    sink.set(null); b.free(); check(sink.read() == 0);
                    Value c = new Value(); failed(() -> new Sink<>(c, true)); c.free();
                    Owner one = new Owner(), two = new Owner();
                    Leaf leaf = one.view(); Keeper<Leaf> keeper = new Keeper<>(leaf);
                    retained(one::free); two.free(); check(keeper.read() == 31);
                    keeper.set(null); one.free(); retained(() -> keeper.set(leaf));
                    check(keeper.read() == 0);
                    Value stable = new Value();
                    var foreign = new java.util.concurrent.atomic.AtomicReference<Value>();
                    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                    var ready = new java.util.concurrent.CountDownLatch(1);
                    var done = new java.util.concurrent.CountDownLatch(1);
                    Thread worker = new Thread(() -> {
                        try {
                            Value local = new Value(); foreign.set(local); ready.countDown();
                            done.await(); local.free();
                        } catch (Throwable error) { failure.set(error); ready.countDown(); }
                    });
                    worker.start(); ready.await();
                    try {
                        check(failure.get() == null);
                        // Externally synchronized transfer is supported; thread
                        // identity is not a bridge world or lifetime identity.
                        sink.set(foreign.get()); retained(foreign.get()::free);
                        Sink<Value> transferred = new Sink<>(foreign.get());
                        check(transferred.read() == 29); transferred.free(); sink.set(null);
                        check(sink.read() == 0);
                    } finally { done.countDown(); worker.join(); }
                    check(failure.get() == null);
                    for (int i = 0; i < 20000; i++) { sink.set(stable); sink.set(null); box.set(first); }
                    long count = Box.allocations();
                    for (int i = 0; i < 20000; i++) { sink.set(stable); sink.set(null); box.set(first); }
                    check(Box.allocations() == count);
                    stable.free(); keeper.free(); sink.free(); other.free(); box.free();
                    System.out.println("bounded-generics-ok");
                }
                private static void oom() {
                    Value value = new Value(); Sink<Value> keeper = new Sink<>(value);
                    long baseline = Box.live();
                    java.util.ArrayList<Sink<Value>> made = new java.util.ArrayList<>();
                    boolean failed = false;
                    try { for (int i = 0; i < 100; i++) made.add(new Sink<>(value)); }
                    catch (OutOfMemoryError expected) { failed = true; }
                    check(failed && keeper.read() == 29); retained(value::free);
                    for (Sink<Value> sink : made) sink.free();
                    check(Box.live() == baseline);
                    keeper.free(); value.free();
                    System.out.println("bounded-oom-ok");
                }
            }
            """;

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
