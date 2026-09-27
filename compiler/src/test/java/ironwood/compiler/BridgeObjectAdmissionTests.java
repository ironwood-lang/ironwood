// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BridgeObjectAdmissionTests {
    static final String NAME = "Java Bridge automatic object admission requires complete final lifetime contracts";
    private static final String ROOT = """
            package rootapi;
            public final class Item {
                public Item() {}
                public Item identity() { return this; }
                public int read() { return 17; }
            }
            """;

    private BridgeObjectAdmissionTests() {}

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            var roots = prove(ROOT, "rootapi", mode);
            check(roots.status() == BridgeProof.Status.PROVED, roots.reason());
            check(roots.contract().orElseThrow().roots().isPresent() && roots.contract().orElseThrow().permanentReasons().isEmpty(),
                    "ordinary root lost its destruction capability");
            var mixed = prove(BridgeMixedLifetimeTests.SOURCE, "mixedlife", mode);
            check(mixed.status() == BridgeProof.Status.PROVED, mixed.reason());
            check(mixed.contract().orElseThrow().permanentReasons().keySet().equals(Set.of(IrType.reference("mixedlife.Holder$Catalog")))
                    && mixed.contract().orElseThrow().roots().orElseThrow().destruction().size() == 2,
                    "automatic mixed classification changed ownership");
            for (String invalid : List.of(
                    BridgeMixedLifetimeTests.SOURCE.replace("private Catalog next;", "private Catalog next; private Item captured;")
                            .replace("public void connect(Catalog value) { next = value; }", "public void connect(Catalog value) { next = value; } public void capture(Item value) { captured = value; }"),
                    BridgeMixedLifetimeTests.SOURCE.replace("store(this, item, catalog, side);", "this.item = this.item;"))) {
                check(prove(invalid, "mixedlife", mode).status() != BridgeProof.Status.PROVED,
                        "explicit root capture/slot transfer became a permanent candidate");
            }
            String permanent = ROOT.replace("public Item() {}", "private static Item saved; public Item() {}")
                    .replace("return this;", "saved = this; return saved;");
            var storage = prove(permanent, "rootapi", mode);
            check(storage.status() == BridgeProof.Status.PROVED && storage.contract().orElseThrow().roots().isEmpty(), storage.reason());
            String owner = permanent.replace("public Item() {}", "private final Child child = new Child(); public Item() {} destructor { free child; }")
                    .replace("public int read() { return 17; }", "public Child child() { return child; } public static final class Child { public Child() {} public int read() { return 17; } }");
            var view = prove(owner, "rootapi", mode);
            check(view.status() == BridgeProof.Status.PROVED && view.contract().orElseThrow().roots().isEmpty()
                    && view.contract().orElseThrow().permanentReasons().get(IrType.reference("rootapi.Item$Child")).stream()
                    .anyMatch(reason -> reason.contains("dependent view")), "permanent owned view lost its lifetime: " + view.reason());
            for (String projected : List.of(
                    "package rootapi; public enum Item { SELL { @Override public int code() { return 29; } }, BUY; public int code() { return 11; } }",
                    "package rootapi; public final class Item { private Item() {} public static int read() throws Problem { throw new Problem(); } public static final class Problem extends Exception { public int getCode() { return 17; } } }")) {
                var projection = prove(projected, "rootapi", mode);
                check(projection.status() == BridgeProof.Status.PROVED && projection.contract().orElseThrow().roots().isEmpty()
                        && projection.contract().orElseThrow().entries().destructions().isEmpty(), "value-only projection: " + projection.reason());
                if (projected.contains("Problem")) {
                    check(projection.contract().orElseThrow().lifetime().references().isEmpty()
                            && projection.contract().orElseThrow().lifetime().exceptions().projection().customTypes().size() == 1,
                            "snapshot-only API acquired native object lifetime state");
                    for (String invalid : List.of(
                            projected.replace("private Item() {}", "private Item() {} private static String saved; public static void retain(String value) { saved = value; }"),
                            projected.replace("private Item() {}", "private Item() {} private static String saved; public static String text() { return saved; }"))) {
                        check(prove(invalid, "rootapi", mode).status() != BridgeProof.Status.PROVED,
                                "empty object lifetime bypassed copied String ownership");
                    }
                }
                if (mode == UnfreedMode.OFF) parity(projected, "rootapi", shape(projection));
            }
            for (String rejected : List.of(
                    ROOT.replace("public Item() {}", "private Item other; public Item() {} public void set(Item value) { other = value; }"),
                    ROOT.replace("public Item identity() { return this; }", "public Item identity(boolean create) { return create ? new Item() : this; }"),
                    permanent.replace("return 17;", "Item item = new Item(); free item; return 17;"),
                    permanent.replace("return 17;", "long ignored = System.nanoTime(); return 17;"))) {
                var failure = prove(rejected, "rootapi", mode);
                check(failure.status() != BridgeProof.Status.PROVED, "automatic permanent fallback admitted unsafe source: " + rejected);
                if (rejected.contains("nanoTime")) check(failure.status() == BridgeProof.Status.UNKNOWN, "unknown effects lost their proof status");
                if (mode == UnfreedMode.OFF) parity(rejected, "rootapi", shape(failure));
            }
            if (mode == UnfreedMode.OFF) {
                parity(ROOT, "rootapi", shape(roots));
                parity(permanent, "rootapi", shape(storage));
                parity(BridgeMixedLifetimeTests.SOURCE, "mixedlife", shape(mixed));
                var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Item.iron", ROOT)));
                var proof = BridgeObjectAdmission.prove(artifact, List.of("rootapi")).contract().orElseThrow();
                check(proof.matches(artifact, proof.surface()), "exact object admission binding missing");
                var changed = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Item.iron", ROOT.replace("17", "19"))));
                check(!proof.matches(changed, proof.surface()), "object admission accepted changed implementation");
            }
        }
        var book = BridgeObjectAdmission.prove(BridgeOrderBookTests.sourceArtifact(), List.of("org.ironwood.orderbook"));
        check(book.status() == BridgeProof.Status.PROVED, "actual OrderBook: " + book.reason());
        check(book.contract().orElseThrow().roots().isEmpty() && book.contract().orElseThrow().permanentReasons().keySet()
                .containsAll(Set.of(IrType.reference("org.ironwood.orderbook.Order"), IrType.reference("org.ironwood.orderbook.OrderBook"))),
                "actual OrderBook acquired fabricated ownership");
        var admittedBook = book.contract().orElseThrow();
        var finalRoots = BridgeRootSet.resolve(admittedBook.program(), admittedBook.program().functions().stream()
                .filter(function -> admittedBook.program().exportRoots().contains(function.linkageName())).map(BridgeCallableId::of).toList());
        for (var type : List.of(IrType.reference("org.ironwood.orderbook.PriceLevel"),
                IrType.array(IrType.reference("org.ironwood.orderbook.PriceLevel")), IrType.array(IrType.reference("org.ironwood.orderbook.Order")),
                IrType.array(IrType.I32))) {
            var proof = ironwood.compiler.semantic.BridgeNonReclamationAnalyzer.analyze(admittedBook.program(), finalRoots, type,
                    admittedBook.lifetime().constructionFacts());
            check(proof.status() == BridgeProof.Status.PROVED, "final internal OrderBook storage: " + proof.reason());
        }
        Path base = Path.of("workspace/java-bridge/evidence/p3a/object-admission").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("orderbook.ll"), bitcode = directory.resolve("orderbook.bc");
        Files.writeString(llvm, new ironwood.compiler.backend.LlvmEmitter().emit(admittedBook.program()));
        Files.writeString(directory.resolve("admission.txt"), "permanent=" + admittedBook.permanentReasons()
                + "\nexports=" + admittedBook.program().exportRoots().stream().sorted().toList()
                + "\nllvm-sha256=" + BridgeGeneration.bytesDigest(Files.readAllBytes(llvm))
                + "\ncompiler-jar-sha256=" + BridgeGeneration.bytesDigest(Files.readAllBytes(Path.of("compiler/build/ironwoodc.jar")))
                + "\nanalysis and LLVM verification only; no OrderBook execution claim\n");
        var discovery = ironwood.compiler.backend.LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        BridgeEntryTests.run(directory, List.of(toolchain.llvmAs().toString(), llvm.toString(), "-o", bitcode.toString()), "assemble");
        BridgeEntryTests.run(directory, List.of(toolchain.opt().toString(), "-passes=verify", bitcode.toString(), "-disable-output"), "verify");
        System.out.println("automatic OrderBook admission evidence: " + directory);
    }

    private static BridgeProof<BridgeObjectAdmission> prove(String text, String exports, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of(exports.equals("rootapi") ? "Item.iron" : "Holder.iron", text)));
        check(artifact.valid(), artifact.diagnostics().toString());
        return BridgeObjectAdmission.prove(artifact, List.of(exports));
    }

    private static Map<String, String> shape(BridgeProof<BridgeObjectAdmission> proof) {
        if (proof.status() != BridgeProof.Status.PROVED) return Map.of("failure", proof.reason());
        var admitted = proof.contract().orElseThrow();
        return Map.of("permanent", admitted.permanentReasons().keySet().toString(),
                "roots", admitted.roots().map(value -> value.destruction().keySet().toString()).orElse("none"),
                "entries", admitted.surface().roots().roots().stream().map(root -> root.callable().linkage()).toList().toString());
    }

    private static void parity(String text, String exports, Map<String, String> expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge automatic admission ");
        try {
            var unit = SourceParser.parse(SourceFile.of(exports.equals("rootapi") ? "Item.iron" : "Holder.iron", text)).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("objects.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of(exports));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                var result = shape(BridgeObjectAdmission.prove(artifact, List.of(exports)));
                check(result.equals(expected), "automatic admission changed after reconstruction: " + result);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
