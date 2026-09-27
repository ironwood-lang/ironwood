// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRootRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class BridgeMixedLifetimeTests {
    static final String NAME = "Java Bridge mixed permanent values preserve root dependencies and final destruction closure";
    private static final IrType HOLDER = IrType.reference("mixedlife.Holder");
    private static final IrType ITEM = IrType.reference("mixedlife.Holder$Item");
    private static final IrType CATALOG = IrType.reference("mixedlife.Holder$Catalog");
    static final String SOURCE = """
            package mixedlife;
            public final class Holder {
                private Item item;
                private Catalog catalog;
                private Side side;
                private final String label;
                public Holder(Item item, Catalog catalog, Side side, String text) {
                    this.item = item; this.catalog = catalog; this.side = side; label = new String(text);
                }
                destructor { free label; }
                public Catalog catalog() { return catalog; }
                public Side side() { return side; }
                public String text() { return label; }
                public void change(Item item, Catalog catalog, Side side) {
                    store(this, item, catalog, side);
                }
                public void fail(Item item, Catalog catalog, Side side) {
                    store(this, item, catalog, side); throw null;
                }
                private static void store(Holder holder, Item item, Catalog catalog, Side side) {
                    holder.item = item; holder.catalog = catalog; holder.side = side;
                }
                public void clear() { item = null; catalog = null; side = null; }
                public enum Side { SELL, BUY; public int code() { return ordinal() + 11; } }
                public static final class Item { public Item() {} public int number() { return 17; } }
                public static final class Catalog {
                    private static Catalog saved;
                    private Catalog next;
                    private final String title = new String("catalog");
                    public Catalog() {}
                    destructor { free title; }
                    public Catalog remember() { saved = this; return saved; }
                    public void connect(Catalog value) { next = value; }
                    public String text() { return title; }
                }
            }
            """;

    private BridgeMixedLifetimeTests() {}

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            var artifact = analyze(SOURCE, mode);
            var shape = shape(artifact);
            check(shape.containsKey("accepted"), shape.toString());
            String withoutEnums = SOURCE.replace("private Side side;", "")
                    .replace("public Side side() { return side; }", "")
                    .replace("public enum Side { SELL, BUY; public int code() { return ordinal() + 11; } }", "")
                    .replace(", Side side", "").replace("this.side = side;", "").replace("holder.side = side;", "")
                    .replace("side = null;", "").replace(", side", "");
            check(shape(analyze(withoutEnums, mode)).containsKey("accepted"), "mixed object proof incorrectly depends on enum conversion");
            var surface = surface(artifact);
            var values = values(artifact, surface);
            var subset = BridgeRootSet.resolve(artifact.program().orElseThrow(), surface.roots().roots().stream()
                    .filter(root -> !root.callable().name().equals("fail")).map(BridgeRootSet.Root::callable).toList());
            check(BridgeRootRetentionAnalyzer.analyze(artifact, subset, values).status() != BridgeProof.Status.PROVED,
                    "mixed lifetime proof accepted a different entry set");
            var changed = analyze(SOURCE.replace("return 17;", "return 19;"), mode);
            check(BridgeRootRetentionAnalyzer.analyze(changed, surface(changed).roots(), values).status() != BridgeProof.Status.PROVED,
                    "mixed lifetime proof accepted changed bodies");
            check(BridgeRootRetentionAnalyzer.analyze(artifact, surface.roots(), BridgeEnumConversions.forSurface(artifact, surface))
                    .status() != BridgeProof.Status.PROVED, "permanent publication accepted without its bound proof");
            if (mode == UnfreedMode.OFF) parity(SOURCE, shape);
            for (String invalid : List.of(
                    SOURCE.replace("private Catalog next;", "private Catalog next; private Item captured;")
                            .replace("public String text() { return title; }", "public String text() { return title; } public void capture(Item value) { captured = value; }"),
                    SOURCE.replace("store(this, item, catalog, side);", "this.item = this.item;"),
                    SOURCE.replace("return saved;", "long ignored = System.nanoTime(); return saved;"),
                    SOURCE.replace("private Catalog catalog;", "private Catalog catalog; private final Catalog owned = new Catalog();")
                            .replace("destructor { free label; }", "destructor { free owned; free label; }"))) {
                var failure = shape(analyze(invalid, mode));
                check(failure.containsKey("rejected"), "unsafe mixed lifetime admitted: " + invalid);
                if (invalid.contains("free owned")) check(failure.get("rejected").contains("dealloc"),
                        "final generated destruction was not the refusal: " + failure);
                if (mode == UnfreedMode.OFF) parity(invalid, failure);
            }
        }
    }

    private static Map<String, String> shape(CompilationArtifact artifact) {
        var surface = surface(artifact);
        try {
            var values = values(artifact, surface);
            var proof = BridgeRootRetentionAnalyzer.analyze(artifact, surface.roots(), values);
            if (proof.status() != BridgeProof.Status.PROVED) return Map.of("rejected", proof.reason());
            var ownership = proof.contract().orElseThrow();
            check(ownership.constructedRootTypes().equals(Set.of(HOLDER, ITEM)) && !ownership.rootSlots().containsKey(CATALOG),
                    "permanent candidate acquired root state or lost reclaimable roots");
            check(ownership.rootSlots().get(HOLDER).stream().map(IrField::name).toList().equals(List.of("item"))
                    && ownership.dependencies().get(HOLDER).equals(Set.of(ITEM)), "permanent field erased a retained dependency");
            var module = BridgeEntryModule.rootObjects(artifact, surface.roots(), values);
            check(module.destructions().size() == 2 && module.destructions().stream()
                    .noneMatch(entry -> entry.function().ownerClass().equals(CATALOG.referenceName())), "permanent destructor generated");
            var finished = BridgeFinalRootRetention.prove(artifact, module, surface);
            if (finished.status() != BridgeProof.Status.PROVED) return Map.of("rejected", finished.reason());
            var contract = finished.contract().orElseThrow();
            check(contract.lifetime().references().containsKey(CATALOG) && contract.rollback().size() == 3
                    && contract.matches(module, contract.program()), "final permanent/root proof inventory incomplete");
            new ironwood.compiler.backend.LlvmEmitter().emit(contract.program());
            var result = new TreeMap<String, String>();
            result.put("accepted", ownership.dependencies().toString());
            ownership.entries().forEach((id, effects) -> result.put(id.linkage(), effects.slots().toString()));
            return result;
        } catch (IllegalArgumentException refused) { return Map.of("rejected", refused.getMessage()); }
    }

    private static BridgePermanentValues values(CompilationArtifact artifact, BridgeExportSurface surface) {
        if (surface.types().stream().noneMatch(type -> type.kind() == ironwood.compiler.semantic.BridgeApiFacts.Kind.ENUM)) {
            return BridgePermanentValues.prove(artifact, surface.roots(), Set.of(CATALOG));
        }
        return BridgePermanentValues.prove(artifact, surface.roots(), Set.of(CATALOG), BridgeEnumConversions.forSurface(artifact, surface));
    }

    private static BridgeExportSurface surface(CompilationArtifact artifact) {
        var selected = BridgeExportSurface.objectValues(artifact, List.of("mixedlife"));
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        return selected.surface().orElseThrow();
    }

    private static CompilationArtifact analyze(String text, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Holder.iron", text)));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static void parity(String text, Map<String, String> expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge mixed lifetime ");
        try {
            var unit = SourceParser.parse(SourceFile.of("Holder.iron", text)).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("mixed.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, classes.resolve("mixedlife/Holder.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("mixedlife"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                check(shape(artifact).equals(expected), "mixed lifetime changed after reconstruction: " + input);
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
