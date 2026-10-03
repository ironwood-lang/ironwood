// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

final class BridgeFinalRootRetentionTests {
    static final String NAME = "Java Bridge final root protocol rechecks slots views getters and exact cleanup";
    private static final String SOURCE = """
            package finalroots;
            public final class Holder {
                private Item item;
                private final View view = new View();
                private final String label = new String("holder");
                public Holder(Item input) { item = input; }
                destructor { free view; free label; }
                public void store(Item input) { item = input; }
                private static int ticks = initial();
                private static int initial() { return 0; }
                private enum Gate { FIRST, SECOND; }
                private void helper(Item input) { if (Gate.FIRST == Gate.SECOND) return; item = input; ticks++; }
                public void repeated(Item input, int count) { for (int i = 0; i < count; i++) { helper(input); helper(input); } }
                public void clear() { item = null; }
                public Item alias(Item input) { return input; }
                public View view() { return view; }
                public String label() { return label; }
                public void fail() { throw new snapshoterrors.Problem(); }
                public static final class Item { public Item() {} }
                public static final class View { private View() {} public int code() { return 17; } }
            }
            """;
    private static final String ENUMS = """
                private Mode side;
                public void side(Mode value) { side = value; }
                public Mode side() { return side; }
                public enum Mode { FIRST, SECOND; public int code() { return ordinal(); } }
            """;
    private static final String ERROR = """
            package snapshoterrors;
            public final class Problem extends RuntimeException {
                public Problem() {}
                public int getCode() { return 29; }
            }
            """;

    private BridgeFinalRootRetentionTests() {}

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) for (boolean enums : List.of(false, true)) {
            String source = enums ? SOURCE.replace("private Item item;", "private Item item; " + ENUMS) : SOURCE;
            var artifact = analyze(source, ERROR, mode);
            var module = module(artifact, enums);
            var proof = BridgeFinalRootRetention.prove(artifact, module);
            check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
            var result = proof.contract().orElseThrow();
            check(result.program().functions().stream().anyMatch(function -> function.sourceName().equals("helper")
                    && function.linkageName().contains(".$initialized.")), "retention fixture lost specialized helper coverage");
            check(result.matches(module, result.program()) && !result.matches(module, module.program()), "final root protocol lost program binding");
            check(result.protocol() == module.rootRetention().orElseThrow(), "final root protocol changed generated slot metadata");
            check(result.protocol().rootSlots().get(IrType.reference("finalroots.Holder")).size() == 1,
                    "final protocol lost Item slot or counted owned/enum fields");
            check(result.protocol().borrowedResultTypes().equals(Set.of(IrType.reference("finalroots.Holder$View"))), "borrowed view owner lost");
            check(result.destruction().keySet().equals(Set.of(IrType.reference("finalroots.Holder"), IrType.reference("finalroots.Holder$Item")))
                    && result.rollback().size() == 2, "final cleanup capability inventory changed");
            check(result.lifetime().references().keySet().equals(enums ? Set.of(IrType.reference("finalroots.Holder$Mode")) : Set.of()),
                    "reclaimable root gained permanent classification");
            for (String error : List.of(ERROR.replace("return 29;", "finalroots.Holder.Item value = new finalroots.Holder.Item(); free value; return 29;"),
                    ERROR.replace("public Problem() {}", "private static finalroots.Holder.Item held; public Problem() {}")
                            .replace("return 29;", "held = new finalroots.Holder.Item(); return 29;"),
                    ERROR.replace("return 29;", "long time = System.nanoTime(); return 29;"))) {
                var changed = analyze(source, error, mode);
                var refused = BridgeFinalRootRetention.prove(changed, module(changed, enums));
                check(refused.status() != BridgeProof.Status.PROVED, "unreported custom getter lifetime effects admitted");
            }
        }
        for (boolean enums : List.of(false, true)) {
            String source = enums ? SOURCE.replace("private Item item;", "private Item item; " + ENUMS) : SOURCE;
            parity(source, ERROR, enums, true);
            parity(source, ERROR.replace("return 29;", "finalroots.Holder.Item value = new finalroots.Holder.Item(); free value; return 29;"), enums, false);
        }
    }

    private static void parity(String holder, String error, boolean enums, boolean accepted) throws Exception {
        Path directory = Files.createTempDirectory("bridge final root ");
        try {
            Path classes = directory.resolve("classes");
            for (var source : List.of(SourceFile.of("Holder.iron", holder), SourceFile.of("Problem.iron", error))) {
                var unit = SourceParser.parse(source).unit().orElseThrow();
                for (var type : DeclaredTypes.in(unit)) {
                    IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
                }
            }
            Path archive = directory.resolve("roots.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, classes.resolve("finalroots/Holder.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(), input.toString().endsWith(IronClass.EXTENSION)
                        ? List.of(input, classes) : List.of(input)).loadBridge(List.of(), List.of("finalroots"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                var proof = BridgeFinalRootRetention.prove(artifact, module(artifact, enums));
                check((proof.status() == BridgeProof.Status.PROVED) == accepted, input + ": " + proof.reason());
                if (!accepted) check(proof.reason().contains("deallocation"), "getter cleanup refusal lost its cause");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static CompilationArtifact analyze(String source, String error, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Holder.iron", source), SourceFile.of("Problem.iron", error)));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static BridgeEntryModule module(CompilationArtifact artifact, boolean enums) {
        var selection = BridgeExportSurface.objectValues(artifact, List.of("finalroots"));
        check(selection.surface().isPresent(), selection.diagnostics().toString());
        var surface = selection.surface().orElseThrow();
        return enums ? BridgeEntryModule.rootObjects(artifact, surface.roots(), BridgeEnumConversions.forSurface(artifact, surface))
                : BridgeEntryModule.rootObjects(artifact, surface.roots());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
