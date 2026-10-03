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

final class BridgeRootEnumTests {
    static final String NAME = "Java Bridge reclaimable enum entries preserve root slots and complete lifetime closure";
    private static final String SOURCE = """
            package rootenum;
            public final class Holder {
                private Item item;
                private Side side;
                private final String label;
                public Holder(Item item, Side side, String text) {
                    this.item = item; this.side = side; label = new String(text);
                }
                destructor { free label; }
                public Side side() { return side; }
                public String text() { return label; }
                public String change(Item value, Side side, String text) {
                    store(this, value, side); return text;
                }
                public void fail(Item value, Side side) { store(this, value, side); throw null; }
                private static void store(Holder holder, Item value, Side side) { holder.item = value; holder.side = side; }
                public void clear() { item = null; side = null; }
                public enum Side {
                    SELL { @Override public int code() { return 29; } }, BUY;
                    private static Side saved;
                    private final String caption = "side";
                    public int code() { return 11; }
                    public String caption() { return caption; }
                    public Side remember(Side value) { saved = value; return saved; }
                }
                public static final class Item { public Item() {} public int number() { return 17; } }
            }
            """;

    private BridgeRootEnumTests() {}

    static void proofs() throws Exception {
        var source = SourceFile.of("Holder.iron", SOURCE);
        for (var mode : UnfreedMode.values()) {
            var artifact = analyze(source, mode);
            var surface = surface(artifact);
            var conversions = BridgeEnumConversions.forSurface(artifact, surface);
            var proof = BridgeRootRetentionAnalyzer.analyze(artifact, surface.roots(), conversions);
            check(proof.status() == BridgeProof.Status.PROVED, proof.status() + ": " + proof.reason());
            var contract = proof.contract().orElseThrow();
            var holder = IrType.reference("rootenum.Holder");
            var item = IrType.reference("rootenum.Holder$Item");
            check(contract.constructedRootTypes().equals(Set.of(holder, item)) && contract.borrowedResultTypes().isEmpty(),
                    "enum values acquired independent ownership or view state");
            check(contract.rootSlots().get(holder).stream().map(IrField::name).toList().equals(List.of("item"))
                    && contract.dependencies().get(holder).equals(Set.of(item)), "enum exemption lost retained root dependency");
            check(contract.resultOrigins().isEmpty() && contract.enumLifetime().isPresent()
                    && contract.analysisRoots().roots().size() > contract.roots().roots().size(), "mixed enum proof closure lost");
            for (var entry : contract.entries().entrySet()) {
                if (Set.of("change", "fail", "clear").contains(entry.getKey().name())) {
                    check(entry.getValue().slots().size() == 1, "helper/exceptional/clear root slot omitted");
                }
            }
            check(BridgeRootRetentionAnalyzer.analyze(artifact, surface.roots()).status() != BridgeProof.Status.PROVED,
                    "enum values bypassed bound lifetime proof");
            var module = module(artifact);
            check(module.destructions().size() == 2 && module.entries().size() == surface.roots().roots().size()
                    && module.enumConversions().isPresent() && module.permanent().isEmpty(), "root and enum lowering capability mismatch");
            check(module.stringResults().size() == 3, "copied and root/enum-borrowed String contracts lost");
            var subset = BridgeRootSet.resolve(artifact.program().orElseThrow(), surface.roots().roots().stream()
                    .filter(root -> !root.callable().name().equals("fail")).map(BridgeRootSet.Root::callable).toList());
            check(BridgeRootRetentionAnalyzer.analyze(artifact, subset, conversions).status() != BridgeProof.Status.PROVED,
                    "subset reused complete mixed proof");
            var changed = analyze(SourceFile.of("Holder.iron", SOURCE.replace("return 29;", "return 31;")), mode);
            check(BridgeRootRetentionAnalyzer.analyze(changed, surface(changed).roots(), conversions).status() != BridgeProof.Status.PROVED,
                    "stale enum conversion authorized root entries");
            if (mode == UnfreedMode.OFF) parity(source, shape(artifact));
            for (String bad : List.of(
                    SOURCE.replace("private static Side saved;", "private static Side saved; private Item captured;")
                            .replace("public int code() { return 11; }", "public int code() { return 11; } public void capture(Item value) { captured = value; }"),
                    SOURCE.replace("private static Side saved;", "private static Side saved; private static Item captured;")
                            .replace("public int code() { return 11; }", "public int code() { return 11; } public void capture(Item value) { captured = value; }"),
                    SOURCE.replace("private Side side;", "private Side side; private Object erased;")
                            .replace("public void clear()", "public void erase(Item item, Side side) { erased = item; erased = side; } public void clear()"),
                    SOURCE.replace("store(this, value, side); return text;", "item = this.item; side = this.side; return text;"),
                    SOURCE.replace("return 29;", "long ignored = System.nanoTime(); return 29;"),
                    SOURCE.replace("private static Side saved;", "private static Side saved; private static long unknown = System.nanoTime();"),
                    SOURCE.replace("public void clear()", "public static void release() { Item item = new Item(); free item; } public void clear()"),
                    SOURCE.replace("private Item item;", "private Item item; private static String retained;")
                            .replace("store(this, value, side); return text;", "retained = text; return text;"),
                    SOURCE.replace("destructor { free label; }", "destructor { free label; long ignored = System.nanoTime(); }"))) {
                var input = SourceFile.of("Holder.iron", bad);
                var negative = analyze(input, mode);
                var outcome = shape(negative);
                check(outcome.containsKey("rejected"), "unsafe mixed root closure admitted: " + bad);
                if (mode == UnfreedMode.OFF) parity(input, outcome);
            }
        }
    }

    private static CompilationArtifact analyze(SourceFile source, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static BridgeExportSurface surface(CompilationArtifact artifact) {
        var selected = BridgeExportSurface.objectValues(artifact, List.of("rootenum"));
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        return selected.surface().orElseThrow();
    }

    private static BridgeEntryModule module(CompilationArtifact artifact) {
        var surface = surface(artifact);
        var module = BridgeEntryModule.rootObjects(artifact, surface.roots(), BridgeEnumConversions.forSurface(artifact, surface));
        new ironwood.compiler.backend.LlvmEmitter().emit(NativeLinkPipeline.finish(NativeLinkPipeline.optimize(module.program())));
        return module;
    }

    private static Map<String, String> shape(CompilationArtifact artifact) {
        try {
            var module = module(artifact);
            var contract = module.rootRetention().orElseThrow();
            Map<String, String> result = new TreeMap<>();
            contract.entries().forEach((id, entry) -> result.put(id.toString(), entry.toString()));
            result.put("slots", contract.rootSlots().entrySet().stream().map(Object::toString).sorted().toList().toString());
            result.put("destruction", module.destructions().stream().map(value -> value.contract().type().toString()).sorted().toList().toString());
            return result;
        } catch (IllegalArgumentException rejected) { return Map.of("rejected", rejected.getMessage()); }
    }

    private static void parity(SourceFile source, Map<String, String> expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge root enum ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("roots.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("rootenum"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid() && shape(artifact).equals(expected), "root enum proof changed after reconstruction: " + input);
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
