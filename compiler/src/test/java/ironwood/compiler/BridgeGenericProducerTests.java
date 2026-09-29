// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

/** P7d1 preserves actual native identities across Java's erased client views. */
final class BridgeGenericProducerTests {
    static final String NAME = "Java Bridge read-only generics preserve finite production and reject input loopholes";
    static final String PRODUCER = "Java Bridge generic producer preserves raw wildcard cast and lifetime behavior";
    private BridgeGenericProducerTests() {}

    static final String BOX = """
            package genericvalues;
            public final class Box<T> {
                private final T value;
                Box(T value) { this.value = value; }
                private static final Quote quoteValue = new Quote();
                private static final Trade tradeValue = new Trade();
                public static Box<Quote> quote() { return new Box<Quote>(quoteValue); }
                public static Box<Trade> trade() { return new Box<Trade>(tradeValue); }
                public static Box<Quote> absent() { return null; }
                public static Box<Quote> empty() { return new Box<Quote>(null); }
                public T get() { return value; }
                public T get(boolean fail) { if (fail) throw new IllegalArgumentException("generic failure"); return value; }
                public Box<T> self() { return this; }
                public static long live() { return System.liveAllocationCount(); }
                public static long allocations() { return System.allocationCount(); }
            }
            """;
    static List<SourceFile> sources(String box) {
        return List.of(SourceFile.of("Box.iron", box),
                SourceFile.of("Quote.iron", "package genericvalues; public final class Quote { public int value() { return 17; } }"),
                SourceFile.of("Trade.iron", "package genericvalues; public final class Trade { public int value() { return 29; } }"),
                SourceFile.of("Bound.iron", """
                        package genericvalues;
                        public final class Bound<T extends Quote> {
                            private final T value;
                            private Bound(T value) { this.value = value; }
                            private static final Quote shared = new Quote();
                            public static Bound<Quote> create() { return new Bound<Quote>(shared); }
                            public T get() { return value; }
                        }
                        """),
                SourceFile.of("Owner.iron", """
                        package genericvalues;
                        public final class Owner {
                            private final Box<Quote> box = new Box<Quote>(null);
                            public Owner() {}
                            destructor { free box; }
                            public Box<Quote> box() { return box; }
                        }
                        """),
                SourceFile.of("Plain.iron", """
                        package genericvalues;
                        public final class Plain {
                            private final Quote value;
                            public Plain(Quote value) { this.value = value; }
                            public Quote get() { return value; }
                        }
                        """));
    }
    static CompilationArtifact analyze(String box) {
        return new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(sources(box));
    }
    static void proofs() {
        var artifact = analyze(BOX);
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("genericvalues"));
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        var domain = BridgeGenericDomain.discover(artifact, proof.contract().orElseThrow().surface().types());
        check(domain.applications().get("genericvalues.Box").size() == 2
                && domain.variables().get("genericvalues.Box#T").size() == 2, "finite alternatives lost");
        var identity = BridgeGeneration.createObjects("generics", artifact, proof.contract().orElseThrow(), "test", "a".repeat(64), "b".repeat(64));
        var declarations = BridgePermanentJavaSources.generateRoots(artifact, proof.contract().orElseThrow(), identity);
        String source = declarations.declarations().sources().get("genericvalues/Box.java");
        check(source.contains("class Box<T>") && source.contains("Box<genericvalues.Quote> quote()")
                && source.contains("public T get()") && !source.contains("public Box("), "Java declaration changed its API");
        for (boolean mixed : List.of(false, true)) {
            var permanent = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(permanentSources(mixed));
            check(permanent.valid(), permanent.diagnostics().toString());
            var admitted = BridgeObjectAdmission.prove(permanent, List.of("genericvalues"));
            check(admitted.status() == BridgeProof.Status.PROVED, admitted.reason());
            check(admitted.contract().orElseThrow().roots().isPresent() == mixed, "permanent family classification changed");
        }
        // Publishing one application cannot make an owned application's destructor safe.
        var publishedOwned = analyze(permanentBox());
        check(publishedOwned.valid(), publishedOwned.diagnostics().toString());
        check(BridgeObjectAdmission.prove(publishedOwned, List.of("genericvalues")).status() != BridgeProof.Status.PROVED,
                "generic family publication hid reachable reclamation");
        for (String rejected : List.of(
                BOX.replace("Box(T value)", "public Box(T value)"),
                BOX.replace("public T get()", "public <U> T get()"),
                BOX.replace("public T get() { return value; }", "public void set(T value) {}"),
                BOX.replace("public T get() { return value; }", "public void consume(Box<T> other) {}"),
                BOX.replace("public T get() { return value; }", "public static Quote read(Box<Quote> other) { return other.get(false); }"),
                BOX.replace("public T get() { return value; }", "public static <U> Box<U> create(U value) { return new Box<U>(value); }"),
                BOX.replace("public T get() { return value; }", "private static <U> Box<U> hidden(U value) { return new Box<U>(value); }"),
                BOX.replace("public T get() { return value; }", "public T get() { long ignored = System.nanoTime(); return value; }"),
                BOX.replace("public T get() { return value; }", "public static Box<int> primitive() { return new Box<int>(1); }"),
                BOX.replace("public T get() { return value; }", "public T[] array() { return null; }"))) {
            var bad = analyze(rejected);
            check(bad.valid(), bad.diagnostics().toString());
            check(BridgeObjectAdmission.prove(bad, List.of("genericvalues")).status() != BridgeProof.Status.PROVED,
                    "unsupported generic API admitted: " + rejected);
        }
        for (String body : List.of("free this;", "free value;")) {
            for (var mode : UnfreedMode.values()) {
                var bad = new CompilerPipeline(mode).analyzeForBridge(sources(BOX.replace("public T get() { return value; }",
                        "public T get() { " + body + " return value; }")));
                check(!bad.valid(), "generic free bypassed mandatory source safety in " + mode);
            }
        }
    }

    private static String permanentBox() {
        return BOX.replace("public static Box<Quote> quote() { return new Box<Quote>(quoteValue); }",
                "private static final Box<Quote> saved = new Box<Quote>(quoteValue); public static Box<Quote> quote() { return saved; }");
    }

    private static List<SourceFile> permanentSources(boolean mixed) {
        return sources(permanentBox()).stream().filter(source -> !source.path().toString().equals("Owner.iron")
                && (mixed || !source.path().toString().equals("Plain.iron") && !source.path().toString().equals("Bound.iron"))).toList();
    }

    static void producer() throws Exception {
        Path base = Path.of("workspace/java-bridge/generics/producer").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        var sources = sources(BOX);
        var inputs = new ArrayList<String>();
        for (var source : sources) {
            Path file = directory.resolve(source.path().getFileName());
            Files.writeString(file, source.content()); inputs.add(file.toString());
        }
        Path classes = directory.resolve("iron-classes"), archive = directory.resolve("generics.ironjar");
        var compile = new ArrayList<>(List.of("--unfreed=off", "-d", classes.toString())); compile.addAll(inputs);
        BridgeProducerTests.command(directory, "compile", 0, compile.toArray(String[]::new));
        IronJar.create(archive, List.of(classes), List.of());
        Properties reference = null;
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (String variant : List.of("source", "classes", "individual", "archive")) {
            Path folder = directory.resolve(variant); Files.createDirectories(folder);
            Path jar = folder.resolve("generics.jar");
            var args = new ArrayList<>(List.of("--java-bridge", "--export", "genericvalues", "--unfreed=off",
                    variant.equals("source") ? "-O0" : "-O3", "-o", jar.toString()));
            if (variant.equals("source")) args.addAll(inputs);
            else {
                String cp = (variant.equals("archive") ? archive : classes).toString();
                if (variant.equals("individual")) {
                    try (var files = Files.walk(classes)) {
                        cp = files.filter(path -> path.toString().endsWith(IronClass.EXTENSION)).sorted()
                                .map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
                    }
                }
                args.addAll(List.of("--source-path", directory.resolve("absent").toString(), "-cp", cp));
            }
            BridgeProducerTests.command(folder, "producer", 0, args.toArray(String[]::new));
            var manifest = new Properties();
            try (var zip = new ZipFile(jar.toFile()); var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) { manifest.load(input); }
            if (reference == null) reference = manifest;
            else for (String key : List.of("generation", "api", "program")) {
                check(reference.getProperty(key).equals(manifest.getProperty(key)), "generic artifact mismatch: " + key);
            }
            Path consumer = folder.resolve("GenericConsumer.java"), output = folder.resolve("consumer-classes");
            Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", output.toString(), consumer.toString()), "javac");
            check(BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni",
                    "-cp", jar + ":" + output, "GenericConsumer"), "consumer").equals("generics-ok\n"), "generic consumer failed");
            check(BridgeEntryTests.run(folder, List.of("env", "IRONWOOD_ALLOCATION_LIMIT=32",
                    javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", jar + ":" + output,
                    "GenericConsumer", "oom"), "consumer-oom").equals("generic-oom-ok\n"), "generic failure containment failed");
        }
        for (boolean mixed : List.of(false, true)) {
            Path folder = directory.resolve(mixed ? "mixed-permanent" : "permanent"); Files.createDirectories(folder);
            Path jar = folder.resolve("generics.jar");
            var args = new ArrayList<>(List.of("--java-bridge", "--export", "genericvalues", "--unfreed=off", "-O3", "-o", jar.toString()));
            for (var source : permanentSources(mixed)) {
                Path file = folder.resolve(source.path().getFileName()); Files.writeString(file, source.content()); args.add(file.toString());
            }
            BridgeProducerTests.command(folder, "producer", 0, args.toArray(String[]::new));
            Path consumer = folder.resolve("PermanentConsumer.java"), output = folder.resolve("consumer-classes");
            Files.writeString(consumer, """
                    import genericvalues.*;
                    public final class PermanentConsumer {
                        public static void main(String[] args) {
                            Box<Quote> box = Box.quote();
                            if (box != Box.quote() || box.self() != box || box.get().value() != 17
                                    || Box.trade().get().value() != 29) throw new AssertionError();
                            for (var method : Box.class.getDeclaredMethods()) {
                                if (method.getName().equals("free")) throw new AssertionError("permanent facade acquired free");
                            }
                            if (box.get().value() != 17) throw new AssertionError();
                            System.out.println("permanent-generics-ok");
                        }
                    }
                    """);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", output.toString(), consumer.toString()), "javac");
            check(BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni",
                    "-cp", jar + ":" + output, "PermanentConsumer"), "consumer").equals("permanent-generics-ok\n"), "permanent consumer failed");
        }
        Path rejected = directory.resolve("rejected"); Files.createDirectories(rejected);
        var badInputs = new ArrayList<String>();
        for (var source : sources(BOX.replace("Box(T value)", "public Box(T value)"))) {
            Path file = rejected.resolve(source.path().getFileName()); Files.writeString(file, source.content()); badInputs.add(file.toString());
        }
        Path badClasses = rejected.resolve("iron-classes"), badArchive = rejected.resolve("bad.ironjar");
        var badCompile = new ArrayList<>(List.of("--unfreed=off", "-d", badClasses.toString())); badCompile.addAll(badInputs);
        BridgeProducerTests.command(rejected, "compile", 0, badCompile.toArray(String[]::new));
        IronJar.create(badArchive, List.of(badClasses), List.of());
        Path preserved = rejected.resolve("preserved.jar"); Files.writeString(preserved, "existing output");
        for (String variant : List.of("source", "classes", "archive")) {
            var args = new ArrayList<>(List.of("--java-bridge", "--export", "genericvalues", "--unfreed=off", "-o", preserved.toString()));
            if (variant.equals("source")) args.addAll(badInputs);
            else args.addAll(List.of("--source-path", rejected.resolve("absent").toString(), "-cp",
                    (variant.equals("archive") ? badArchive : badClasses).toString()));
            String diagnostics = BridgeProducerTests.command(rejected, "refused-" + variant, 1, args.toArray(String[]::new));
            check(diagnostics.contains("inaccessible constructors") && Files.readString(preserved).equals("existing output"),
                    "unsupported generic construction changed output or lost its diagnostic: " + diagnostics);
        }
        System.out.println("generic producer evidence: " + directory);
    }

    static final String CONSUMER = """
            import genericvalues.*;
            public final class GenericConsumer {
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                @SuppressWarnings({"rawtypes", "unchecked"})
                public static void main(String[] args) throws Exception {
                    check(Box.absent() == null);
                    if (args.length != 0) {
                        Box<Quote> keeper = Box.quote(); Quote value = keeper.get();
                        long live = Box.live(); boolean failed = false;
                        for (int i = 0; i < 100; i++) {
                            try { Box<Quote> temporary = Box.quote(); temporary.free(); }
                            catch (OutOfMemoryError expected) { failed = true; }
                            check(keeper.get() == value && value.value() == 17 && Box.live() == live);
                        }
                        check(failed); keeper.free(); check(Box.live() == live - 1);
                        System.out.println("generic-oom-ok"); return;
                    }
                    long baseline = Box.live();
                    Box<Quote> quote = Box.quote(); Box<Trade> trade = Box.trade(); Box<Quote> empty = Box.empty();
                    Quote q = quote.get(); Trade t = trade.get();
                    check(q.value() == 17 && t.value() == 29 && empty.get() == null);
                    Bound<Quote> bound = Bound.create();
                    check(bound.get().value() == 17 && Bound.class.getTypeParameters()[0].getBounds()[0] == Quote.class);
                    bound.free();
                    check(quote.self() == quote && trade.self() == trade);
                    Box<?> wildcard = quote; Box raw = trade;
                    check(wildcard.get() == q && raw.get() == t);
                    Box<? extends Quote> upper = quote; Box<? super Quote> lower = quote;
                    check(upper.get() == q && lower.get() == q);
                    Box<Quote> lied = (Box<Quote>) raw;
                    check(((Box<?>) lied).get() == t);
                    try { Quote invalid = lied.get(); throw new AssertionError(invalid); }
                    catch (ClassCastException expected) { }
                    // Java-only type arguments never request a native specialization.
                    Box<String> javaOnly = (Box<String>) (Box<?>) quote;
                    check(((Box<?>) javaOnly).get() == q);
                    try { String invalid = javaOnly.get(); throw new AssertionError(invalid); }
                    catch (ClassCastException expected) { }
                    check(trade.get() == t && quote.equals(quote) && !quote.equals(trade));
                    check(quote.hashCode() == quote.hashCode() && quote.toString().startsWith("genericvalues.Box@"));
                    try { quote.get(true); throw new AssertionError(); }
                    catch (IllegalArgumentException expected) { check(expected.getMessage().equals("generic failure")); }
                    check(quote.get(false) == q);
                    Owner owner = new Owner(); Box<Quote> borrowed = owner.box();
                    check(owner.box() == borrowed && borrowed.get() == null && borrowed.self() == borrowed);
                    try { borrowed.free(); throw new AssertionError(); } catch (IllegalStateException expected) { }
                    owner.free();
                    try { borrowed.get(); throw new AssertionError(); } catch (IllegalStateException expected) { }
                    long allocations = Box.allocations();
                    for (int i = 0; i < 20000; i++) check(quote.get() == q && trade.get() == t);
                    check(Box.allocations() == allocations);
                    check(Box.class.getTypeParameters().length == 1
                            && Box.class.getMethod("get").getGenericReturnType() instanceof java.lang.reflect.TypeVariable<?>
                            && Box.class.getMethod("quote").getGenericReturnType() instanceof java.lang.reflect.ParameterizedType);
                    long beforeFree = Box.live();
                    quote.free(); trade.free(); empty.free();
                    check(Box.live() == beforeFree - 3);
                    try { wildcard.get(); throw new AssertionError(); } catch (IllegalStateException expected) { }
                    // The caught native exception follows the existing process lifetime.
                    check(Box.live() >= baseline);
                    check(q.value() == 17 && t.value() == 29);
                    System.out.println("generics-ok");
                }
            }
            """;

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
