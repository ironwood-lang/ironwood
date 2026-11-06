// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.GcManagedHeapVerifier;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Replacement families separate managed reachability from native protocols. */
final class Gc7ConsumerTests {
    static final String SYNTAX = "GC7 removed forms retain migration errors and ordinary identifiers";
    static final String GRAPHS = "GC7 shared graph and result origin families use managed admission";
    static final String INITIALIZATION = "GC7 partial publication retains initialization checks";
    static final String NATIVE = "GC7 copied inputs and borrowed descriptors retain confinement";
    static final String ARTIFACTS = "GC7 shared consumers preserve source class and archive parity";
    static final String BOUNDED = "GC7 bounded compiler check has no managed free proof machinery";
    private Gc7ConsumerTests() {}

    static void removedSyntax() {
        for (String member : List.of("void old() { free this.next; }", "void old() { defer free this.next; }",
                "destructor {}", "@SuppressUnfreed Object next;", "void old() { free new Object(); }",
                "void old() { Object next = null; defer free next; }")) {
            var source = SourceFile.of("Old.iron", "class Old { Object next; " + member + " }");
            var result = new CompilerPipeline().analyze(List.of(source));
            check(!result.valid() && result.diagnostics().stream().anyMatch(d -> d.message().contains("removed")
                    && d.source() != null && d.source().path().equals(source.path())), result.diagnostics().toString());
        }
        var names = new CompilerPipeline().analyze(List.of(SourceFile.of("Names.iron", """
                class free { }
                class destructor { free item = new free(); }
                class Names {
                    static int destructor(int free) { free++; return free; }
                    static void free(Object destructor) { }
                    static void use() { free item = new free(); Object free = item; defer free(free); }
                }
                """)));
        check(names.valid(), names.diagnostics().toString());
        for (String option : List.of("--unfreed", "--unfreed=off", "--unfreed=warn", "--unfreed=error", "--explain-rejected-free", "--explain-rejected-free=all")) {
            var bytes = new java.io.ByteArrayOutputStream();
            var stream = new java.io.PrintStream(bytes);
            check(Main.run(new String[]{option}, stream, stream) != 0 && bytes.toString().contains("removed"), option);
            bytes.reset();
            check(Main.run(new String[]{"--java-bridge", option}, stream, stream) != 0
                    && bytes.toString().contains("removed"), "bridge " + option);
        }
    }

    private static List<String> graphs() {
        return List.of(
                "this.next = other; other.next = this; return other;",
                "this.next = other.next; return flag ? this : other;",
                "Case[] values = new Case[2]; values[0] = other; values[1] = other; this.next = values[0]; return values[1];",
                "Case made = new Case(); made.next = other; this.next = made; return made;",
                "this.next = other; other = null; ironwood.lang.Memory.collect(); return this.next;",
                "Case value = this; if (flag) value = other; defer ironwood.lang.Memory.collect(); return value;",
                "Case[] first = new Case[1]; Case[] second = new Case[1]; first[0] = other; System.arraycopy(first, 0, second, 0, 1); return second[0];",
                "this.next = other; return this.next.next == null ? other : this.next.next;");
    }

    private static SourceFile graph(String body) {
        return SourceFile.of("Case.iron", "package gc7consumer; public final class Case { private Case next; "
                + "public Case() {} public Case work(Case other, boolean flag) { " + body + " } }");
    }

    static void aliasesAndOrigins() {
        for (String body : graphs()) {
            var source = graph(body);
            var artifact = new CompilerPipeline().analyzeForBridge(List.of(source));
            check(artifact.valid(), artifact.diagnostics().toString());
            var admission = BridgeObjectAdmission.proveManaged(artifact, List.of("gc7consumer"));
            check(admission.status() == BridgeProof.Status.PROVED, admission.reason());
            GcManagedHeapVerifier.verifyBridge(admission.contract().orElseThrow().program());
        }
    }

    static void initialization() {
        var safe = new CompilerPipeline().analyze(List.of(SourceFile.of("Init.iron", """
                class Init {
                    static Init published;
                    final Object value;
                    Init(boolean fail) {
                        value = new Object();
                        published = this;
                        if (fail) throw new IllegalStateException();
                    }
                }
                """)));
        check(safe.valid(), safe.diagnostics().toString());
        for (String source : List.of("class Bad { final int value; Bad() {} }",
                "class Bad { final int value; Bad() { value = 1; value = 2; } }")) {
            var bad = new CompilerPipeline().analyze(List.of(SourceFile.of("Bad.iron", source)));
            check(!bad.valid() && bad.diagnostics().stream().anyMatch(d -> d.message().contains("final")), bad.diagnostics().toString());
        }
    }

    static void nativeContracts() {
        for (boolean retain : List.of(false, true)) {
            String member = retain ? "saved = values; return values[0];" : "values[0] = 42; return values[0];";
            var artifact = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Arrays.iron",
                    "package gc7native; public final class Arrays { private static int[] saved; "
                    + "public static int use(int[] values) { " + member + " } }")));
            check(artifact.valid(), artifact.diagnostics().toString());
            var admission = BridgeObjectAdmission.proveManaged(artifact, List.of("gc7native"));
            check((admission.status() == BridgeProof.Status.PROVED) != retain, admission.reason());
        }
        var bad = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Views.iron", """
                package gc7native;
                public final class Views {
                    private static ironwood.bridge.ByteView saved;
                    public static void keep(ironwood.bridge.ByteView view) { saved = view; }
                }
                """)));
        check(!bad.valid() || BridgeObjectAdmission.proveManaged(bad, List.of("gc7native")).status() != BridgeProof.Status.PROVED,
                "borrowed descriptor escaped its foreign lifetime");
    }

    static void snapshotConfinement() {
        for (boolean publish : List.of(false, true)) {
            String body = publish ? "saved = this; return \"snapshot\";"
                    : "ironwood.lang.Memory.collect(); return new String(\"snapshot\");";
            var artifact = new CompilerPipeline().analyzeForBridge(List.of(
                    SourceFile.of("Fault.iron", "package gc7snap; public final class Fault extends RuntimeException { "
                            + "private static Fault saved; public Fault() { super(\"snapshot\"); } "
                            + "@Override public String getMessage() { " + body + " } }"),
                    SourceFile.of("Api.iron", "package gc7snap; public final class Api { "
                            + "private Api() {} public static void fail() { throw new Fault(); } }")));
            check(artifact.valid(), artifact.diagnostics().toString());
            var admission = BridgeObjectAdmission.proveManaged(artifact, List.of("gc7snap"));
            check((admission.status() == BridgeProof.Status.PROVED) != publish, admission.reason());
        }
    }

    static void artifacts() throws Exception {
        Path root = Files.createTempDirectory(Path.of("workspace"), "gc7-parity-");
        int index = 0;
        for (String body : graphs()) {
            var source = graph(body);
            var parsed = SourceParser.parse(source);
            check(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
            Path phase = root.resolve("case-" + index++);
            Files.createDirectories(phase);
            Path loose = phase.resolve("Case.ironclass"), archive = phase.resolve("case.ironjar");
            IronClass.write(loose, parsed.unit().orElseThrow(), "gc7consumer.Case");
            IronJar.create(archive, List.of(loose));
            for (var reconstructed : List.of(source, IronClass.read(loose).source("gc7consumer.Case").orElseThrow(),
                    IronJar.read(archive).source("gc7consumer.Case").orElseThrow())) {
                check(reconstructed.content().equals(source.content()), "embedded source changed");
                var result = new CompilerPipeline().analyzeForBridge(List.of(reconstructed));
                check(result.valid(), result.diagnostics().toString());
                var admission = BridgeObjectAdmission.proveManaged(result, List.of("gc7consumer"));
                check(admission.status() == BridgeProof.Status.PROVED, admission.reason());
            }
        }
    }

    static void boundedCompilation() throws Exception {
        for (String name : List.of("OwnedArrayFieldAnalyzer", "FieldRetentionAnalysis", "OwnedArrayElementAnalyzer", "TemporaryBorrowAnalysis",
                "TemporaryListBorrowAnalysis", "ClosedWorldEffectAnalyzer", "HeldFieldLoads", "FreshArrayElementAnalysis", "MapCopyFactoryAnalysis", "SetCopyFactoryAnalysis", "CopiedMapReadAnalysis", "RejectedFreeEvidence", "SummaryWitnessEvidence")) {
            try { Class.forName("ironwood.compiler.semantic." + name); throw new AssertionError("retained obsolete analysis " + name); }
            catch (ClassNotFoundException expected) { }
        }
        var source = new StringBuilder("class Case { static Node0 graph; static void run() { graph = new Node0(); } }\n");
        for (int i = 0; i < 48; i++) source.append("class Node").append(i).append(" { Object shared; Node")
                .append((i + 1) % 48).append(" next; Node").append(i).append(" self() { return this; } }\n");
        var result = new CompilerPipeline().analyze(List.of(SourceFile.of("Bounded.iron", source.toString())));
        check(result.valid() && result.program().orElseThrow().classes().size() >= 49, result.diagnostics().toString());
    }

    static void staleBridgeManifests() {
        var artifact = new CompilerPipeline().analyzeForBridge(List.of(graph(graphs().getFirst())));
        var proof = BridgeObjectAdmission.proveManaged(artifact, List.of("gc7consumer"));
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        var generation = ironwood.compiler.bridge.BridgeGeneration.createObjects("gc7consumer", artifact,
                proof.contract().orElseThrow(), "test", "a".repeat(64), "b".repeat(64));
        check(ironwood.compiler.bridge.BridgeGeneration.fromManifest(generation.manifest()).identity()
                .equals(generation.identity()), "current manifest did not round trip");
        for (var change : java.util.Map.of("schema", "1", "projection", "objects-v1",
                "roots.version", "2", "runtime.owner.version", "1", "foreign.segments.version", "0").entrySet()) {
            var stale = new java.util.TreeMap<>(generation.manifest());
            stale.put(change.getKey(), change.getValue());
            boolean rejected = false;
            try { ironwood.compiler.bridge.BridgeGeneration.fromManifest(stale); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "stale bridge manifest admitted " + change.getKey());
        }
    }

    static void poolOrigins() {
        String prefix = "import ironwood.pool.*; class Item {} class Wrapper { private final Item item; Wrapper(Item item) { this.item = item; } Item get() { return this.item; } } class Builder implements ObjectBuilder<Item> { @Override public Item newInstance() { return new Item(); } } class Main { static Item echo(Item value) { return value; } static void use() { ObjectPool<Item> pool = new ArrayObjectPool<Item>(1, new Builder()); ObjectPool<Item> other = new MultiArrayObjectPool<Item>(1, new Builder()); ";
        for (String body : List.of("Item item = pool.get(); Item alias = item; pool.release(alias);",
                "Item[] items = {pool.get()}; pool.release(items[0]);",
                "Item item = echo(pool.get()); pool.release(item);",
                "Wrapper wrapper = new Wrapper(pool.get()); pool.release(wrapper.get());")) {
            var safe = new CompilerPipeline().analyze(List.of(SourceFile.of("Main.iron", prefix + body + " }}")));
            check(safe.valid(), safe.diagnostics().toString());
            var wrong = new CompilerPipeline().analyze(List.of(SourceFile.of("Main.iron", prefix + body.replace("pool.release", "other.release") + " }}")));
            check(!wrong.valid() && wrong.diagnostics().stream().anyMatch(d -> d.message().contains("owned by another pool")), body + ": " + wrong.diagnostics());
        }
        for (String declaration : List.of(
                "class Item { static Item saved; Item() { saved = this; } }",
                "class Parent { static Object saved; Parent() { saved = this; } } class Item extends Parent {}",
                "class Parent { static Object saved; Parent(int value) { saved = this; } } class Item extends Parent { Item() { super(1); } }")) {
            var publishingBuilder = new CompilerPipeline().analyze(List.of(SourceFile.of("Main.iron",
                    prefix.replace("class Item {}", declaration) + " }}")));
            check(!publishingBuilder.valid() && publishingBuilder.diagnostics().stream().anyMatch(d ->
                    d.message().contains("fresh unescaped")), "pool builder publication was admitted: " + publishingBuilder.diagnostics());
        }
        var quietParent = new CompilerPipeline().analyze(List.of(SourceFile.of("Main.iron",
                prefix.replace("class Item {}", "class Parent { Parent() {} } class Item extends Parent {}") + " }}")));
        check(quietParent.valid(), "quiet superclass rejected: " + quietParent.diagnostics());
        var unknownBuilder = new CompilerPipeline().analyze(List.of(SourceFile.of("Unknown.iron", """
                import ironwood.pool.*;
                class Item {}
                interface Factory { Item create(); }
                class Builder implements ObjectBuilder<Item> {
                    private final Factory factory;
                    Builder(Factory factory) { this.factory = factory; }
                    @Override public Item newInstance() { return factory.create(); }
                }
                """)));
        check(!unknownBuilder.valid() && unknownBuilder.diagnostics().stream().anyMatch(d ->
                d.message().contains("fresh unescaped")), "unknown builder effect was admitted: " + unknownBuilder.diagnostics());
    }

    static void checkedInCompilerConsumers() throws Exception {
        try (var paths = Files.walk(Path.of("compiler/src/main/ironwood"))) {
            for (Path path : paths.filter(value -> value.toString().endsWith(".iron")).sorted().toList()) {
                var result = SourceParser.parse(SourceFile.read(path));
                check(result.unit().isPresent() && result.diagnostics().isEmpty(), path + ": " + result.diagnostics());
            }
        }
    }

    static void genericConsumers() throws Exception {
        for (String example : List.of("generics", "bounded-generics")) {
            var sources = new ArrayList<SourceFile>();
            try (var paths = Files.list(Path.of("examples/java-bridge", example))) {
                for (Path path : paths.filter(value -> value.toString().endsWith(".iron")).sorted().toList()) sources.add(SourceFile.read(path));
            }
            String exported = example.equals("generics") ? "genericbench" : "boundedbench";
            var artifact = new CompilerPipeline().analyzeForBridge(sources);
            check(artifact.valid(), artifact.diagnostics().toString());
            var accepted = BridgeObjectAdmission.proveManaged(artifact, List.of(exported));
            check(accepted.status() == BridgeProof.Status.PROVED, accepted.reason());
        }
        var unsupported = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Generic.iron",
                "package gc7generic; public final class Generic { public static <T> T echo(T value) { return value; } }")));
        check(unsupported.valid(), unsupported.diagnostics().toString());
        check(BridgeObjectAdmission.proveManaged(unsupported, List.of("gc7generic")).status() != BridgeProof.Status.PROVED,
                "unbounded generic method export was admitted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
