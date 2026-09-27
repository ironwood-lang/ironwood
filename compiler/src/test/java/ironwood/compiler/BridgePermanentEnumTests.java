// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgePermanentAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BridgePermanentEnumTests {
    static final String NAME = "Java Bridge permanent mixed enum entries preserve complete lifetime and cleanup proofs";
    private static final String SOURCE = """
            package permanentenum;
            public final class Catalog {
                private static Catalog saved;
                private static Side savedSide;
                private final String label;
                private final Side side;
                public Catalog(Side side, String text) { this.side = side; label = new String(text); }
                destructor { free label; }
                public Side side() { saved = this; return side; }
                public String text() { saved = this; return label; }
                public String alias(Side side, String input) { saved = this; savedSide = side; return input; }
                public Catalog peer(Catalog other, Side side, String text) { saved = other; savedSide = side; return other; }
                public static Catalog unknown() { return saved; }
                public enum Side {
                    SELL(29) { @Override public int code() { return 31; } }, BUY(11);
                    private final int number;
                    Side(int number) { this.number = number; }
                    public int code() { return number; }
                    public Catalog remember(Catalog value, String text) { saved = value; return value; }
                }
            }
            """;

    private BridgePermanentEnumTests() {}

    static void proofs() throws Exception {
        var source = SourceFile.of("Catalog.iron", SOURCE);
        for (var mode : UnfreedMode.values()) {
            var artifact = analyze(source, mode);
            var surface = surface(artifact);
            var conversions = BridgeEnumConversions.forSurface(artifact, surface);
            var proof = BridgePermanentAnalyzer.analyze(artifact, surface.roots(), conversions);
            check(proof.status() == BridgeProof.Status.PROVED, proof.status() + ": " + proof.reason());
            var contract = proof.contract().orElseThrow();
            check(contract.references().containsKey(IrType.reference("permanentenum.Catalog"))
                    && contract.references().containsKey(IrType.reference("permanentenum.Catalog$Side"))
                    && !contract.references().containsKey(IrType.reference("ironwood.lang.String")), "mixed lifetime classification lost");
            check(contract.rollbacks().size() == 1 && contract.roots().roots().stream().anyMatch(root ->
                    root.callable().kind() == IrCallableKind.CLASS_INITIALIZER), "rollback or conversion closure omitted");
            var module = BridgeEntryModule.permanentObjects(artifact, surface.roots(), conversions);
            check(module.enumConversions().orElseThrow() == conversions && module.enumInvocation().isEmpty()
                    && module.rootRetention().isEmpty() && module.destructions().isEmpty(), "mixed conversion acquired root state");
            check(module.entries().size() == surface.roots().roots().size(), "conversion initializer became a public entry");
            check(module.stringResults().size() == 2, "mixed String result contracts lost");
            new ironwood.compiler.backend.LlvmEmitter().emit(NativeLinkPipeline.finish(NativeLinkPipeline.optimize(module.program())));
            var changed = analyze(SourceFile.of("Catalog.iron", SOURCE.replace("return 31;", "return 32;")), mode);
            check(BridgePermanentAnalyzer.analyze(changed, surface(changed).roots(), conversions).status() != BridgeProof.Status.PROVED,
                    "stale enum conversion facts authorized permanent invocation");
            denied(() -> BridgeEntryModule.permanentObjects(changed, surface(changed).roots(), conversions));
            var subset = BridgeRootSet.resolve(artifact.program().orElseThrow(), surface.roots().roots().stream()
                    .filter(root -> !root.callable().name().equals("peer")).map(BridgeRootSet.Root::callable).toList());
            check(BridgePermanentAnalyzer.analyze(artifact, subset, conversions).status() != BridgeProof.Status.PROVED,
                    "different export roots reused conversion permission");
            if (mode == UnfreedMode.OFF) parity(source, shape(artifact));
            for (String badSource : List.of(
                    SOURCE.replace("return 31;", "long ignored = System.nanoTime(); return 31;"),
                    SOURCE.replace("private final int number;", "private final int number; private static long unknown = System.nanoTime();"),
                    SOURCE.replace("private static Side savedSide;", "private static Side savedSide; private static String retained;")
                            .replace("saved = other;", "retained = text; saved = other;"),
                    SOURCE.replace("public static Catalog unknown()", "public static void release() { Catalog value = new Catalog(null, \"x\"); free value; } public static Catalog unknown()"),
                    SOURCE.replace("private final String label;", "private final String label; private final Child child = new Child(); "
                            + "private static final class Child { destructor { long ignored = System.nanoTime(); } }")
                            .replace("destructor { free label; }", "destructor { free child; free label; }"))) {
                var input = SourceFile.of("Catalog.iron", badSource);
                var bad = analyze(input, mode);
                var result = shape(bad);
                check(result.containsKey("rejected"), "unsafe mixed permanent closure admitted: " + badSource);
                var badSurface = surface(bad);
                denied(() -> BridgeEntryModule.permanentObjects(bad, badSurface.roots(), BridgeEnumConversions.forSurface(bad, badSurface)));
                if (mode == UnfreedMode.OFF) parity(input, result);
            }
            var unsafe = List.of(SourceFile.of("Catalog.iron", SOURCE.replace("public static Catalog unknown()",
                    "public static void release(Catalog value) { free value; } public static Catalog unknown()")));
            var rejected = new CompilerPipeline(mode).analyzeForBridge(unsafe);
            check(!rejected.valid() && rejected.diagnostics().equals(new CompilerPipeline(mode).analyze(unsafe).diagnostics()),
                    "mixed conversion changed mandatory parameter-free safety");
        }
    }

    private static CompilationArtifact analyze(SourceFile source, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static BridgeExportSurface surface(CompilationArtifact artifact) {
        var selection = BridgeExportSurface.objectValues(artifact, List.of("permanentenum"));
        check(selection.surface().isPresent(), selection.diagnostics().toString());
        return selection.surface().orElseThrow();
    }

    private static Map<String, String> shape(CompilationArtifact artifact) {
        var surface = surface(artifact);
        var conversions = BridgeEnumConversions.forSurface(artifact, surface);
        var proof = BridgePermanentAnalyzer.analyze(artifact, surface.roots(), conversions);
        if (proof.status() != BridgeProof.Status.PROVED) return Map.of("rejected", proof.status() + ":" + proof.reason());
        var contract = proof.contract().orElseThrow();
        var result = new TreeMap<String, String>();
        result.put("types", contract.references().keySet().stream().map(IrType::displayName).sorted().toList().toString());
        result.put("closure", contract.roots().roots().stream().map(root -> root.callable().toString()).toList().toString());
        result.put("rollback", contract.rollbacks().keySet().stream().map(Object::toString).sorted().toList().toString());
        BridgeStringResults.proveForPermanent(artifact, contract.roots(), contract).forEach((id, value) -> {
            check(value.status() == BridgeProof.Status.PROVED, value.status() + ":" + value.reason());
            result.put("String:" + id, value.contract().orElseThrow().toString());
        });
        return result;
    }

    private static void parity(SourceFile source, Map<String, String> expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge permanent enum ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("objects.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("permanentenum"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid() && shape(artifact).equals(expected), "mixed permanent proof changed after reconstruction: " + input);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void denied(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unproved mixed permanent entry admitted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
