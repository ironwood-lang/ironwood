// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.semantic.BridgeDestructionAnalyzer;
import ironwood.compiler.semantic.BridgeRootRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BridgeObjectStringTests {
    static final String NAME = "Java Bridge object String proofs preserve owner lifetime and copied-input confinement";
    private static final String SOURCE = """
            package objectstrings;
            public final class Label {
                private final String label;
                public Label(String input) { label = new String(input); }
                destructor { free label; }
                public String text() { return label; }
                public String copied(String input) { return new String(input); }
                public String alias(String input) { return input; }
                public static String literal() { return "label"; }
                public String nullable(boolean absent) { return absent ? null : label; }
                public int length(String input) { return input == null ? -1 : input.length(); }
            }
            """;

    private BridgeObjectStringTests() {}

    static void proofs() throws Exception {
        var source = SourceFile.of("Label.iron", SOURCE);
        Map<String, String> expected = Map.of();
        for (var mode : UnfreedMode.values()) {
            var artifact = analyze(source, mode);
            var roots = roots(artifact);
            var ownership = BridgeRootRetentionAnalyzer.analyze(artifact, roots);
            check(ownership.status() == BridgeProof.Status.PROVED, ownership.toString());
            var contract = ownership.contract().orElseThrow();
            check(contract.borrowedResultTypes().isEmpty(), "copied String gained a native facade");
            check(contract.resultOrigins().isEmpty(), "String result entered object identity protocol");
            var values = BridgeStringResults.proveForRoots(artifact, roots, contract);
            check(values.size() == 5, "missing String result contracts");
            for (var entry : values.entrySet()) {
                check(entry.getValue().status() == BridgeProof.Status.PROVED, entry.toString());
                var value = entry.getValue().contract().orElseThrow();
                var kind = switch (entry.getKey().name()) {
                    case "text", "nullable" -> BridgeStringResultContract.Kind.BORROWED;
                    case "copied" -> BridgeStringResultContract.Kind.FRESH;
                    case "alias" -> BridgeStringResultContract.Kind.INPUT_ALIAS;
                    default -> BridgeStringResultContract.Kind.IMMORTAL;
                };
                check(value.kind() == kind, value.toString());
                check(value.releaseAfterCopy() == (kind == BridgeStringResultContract.Kind.FRESH
                        || kind == BridgeStringResultContract.Kind.INPUT_ALIAS), "borrowed storage gained release authority");
                if (kind == BridgeStringResultContract.Kind.BORROWED) check(value.inputs().equals(java.util.Set.of(0)),
                        "getter lost exact receiver owner");
                if (kind == BridgeStringResultContract.Kind.INPUT_ALIAS) check(value.inputs().equals(java.util.Set.of(1)),
                        "temporary String alias confused with receiver");
            }
            check(BridgeDestructionAnalyzer.analyze(artifact, roots, contract.constructedRootTypes().iterator().next())
                    .status() == BridgeProof.Status.PROVED, "owned String field lost nonthrowing destruction proof");
            denied(() -> BridgeEntryModule.rootObjects(artifact, roots), "String conversion lowering");
            check(BridgeExportSurface.valuePreview(artifact, List.of("objectstrings")).surface().isEmpty(),
                    "incomplete object converter became public");
            var old = BridgeStringResults.prove(artifact, roots);
            check(old.entrySet().stream().filter(entry -> entry.getKey().name().equals("text"))
                    .noneMatch(entry -> entry.getValue().status() == BridgeProof.Status.PROVED), "scalar mode gained object cleanup");
            expected = shape(values);

            for (String method : List.of(
                    "private String saved; public void capture(String input) { saved = input; }",
                    "private static String saved; public String publish(String input) { saved = input; return input; }",
                    "public String mixed(boolean select, String input) { return select ? new String(input) : input; }",
                    "private static String saved; public String unknown() { return saved; }")) {
                var badSource = SourceFile.of("Label.iron", SOURCE.replace("public int length(String input)",
                        method + " public int length(String input)"));
                var bad = analyze(badSource, mode);
                var badRoots = roots(bad);
                var badOwnership = BridgeRootRetentionAnalyzer.analyze(bad, badRoots);
                if (badOwnership.status() == BridgeProof.Status.PROVED) {
                    check(BridgeStringResults.proveForRoots(bad, badRoots, badOwnership.contract().orElseThrow())
                            .values().stream().anyMatch(proof -> proof.status() != BridgeProof.Status.PROVED),
                            "unsafe combined conversion admitted: " + method);
                }
                if (mode == UnfreedMode.OFF) parity(badSource, combinedShape(bad, badRoots, badOwnership));
            }
            var changed = analyze(SourceFile.of("Label.iron", SOURCE.replace("-1", "-2")), mode);
            denied(() -> BridgeStringResults.proveForRoots(changed, roots(changed), contract), "matching complete root ownership");
            var subset = BridgeRootSet.resolve(artifact.program().orElseThrow(), roots.roots().stream()
                    .filter(root -> !root.callable().name().equals("length")).map(BridgeRootSet.Root::callable).toList());
            denied(() -> BridgeStringResults.proveForRoots(artifact, subset, contract), "matching complete root ownership");
        }
        parity(source, expected);
        var views = SourceFile.of("Label.iron", SOURCE.replace("private final String label;", """
                private final String label;
                private final View child = new View();
                public View view() { return child; }
                public static final class View {
                    private final String text = new String("view");
                    private View() {}
                    public String text() { return text; }
                    destructor { free text; }
                }
                """).replace("destructor { free label; }", "destructor { free label; free child; }"));
        var artifact = analyze(views, UnfreedMode.OFF);
        var roots = roots(artifact);
        var ownership = BridgeRootRetentionAnalyzer.analyze(artifact, roots);
        check(ownership.status() == BridgeProof.Status.PROVED, ownership.toString());
        var values = BridgeStringResults.proveForRoots(artifact, roots, ownership.contract().orElseThrow());
        check(values.values().stream().allMatch(proof -> proof.status() == BridgeProof.Status.PROVED), values.toString());
        check(values.entrySet().stream().anyMatch(entry -> entry.getKey().owner().endsWith("$View")
                && entry.getValue().contract().orElseThrow().kind() == BridgeStringResultContract.Kind.BORROWED),
                "view getter lost shared root lifetime");
        parity(views, shape(values));
    }

    private static CompilationArtifact analyze(SourceFile source, UnfreedMode mode) {
        var result = new CompilerPipeline(mode).analyzeForBridge(List.of(source));
        check(result.valid(), result.diagnostics() + "\n" + source.content());
        return result;
    }

    private static BridgeRootSet roots(CompilationArtifact artifact) {
        var selected = BridgeExportSurface.concreteObjects(artifact, List.of("objectstrings"));
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        return selected.surface().orElseThrow().roots();
    }

    private static Map<String, String> shape(Map<BridgeCallableId, BridgeProof<BridgeStringResultContract>> proofs) {
        var result = new TreeMap<String, String>();
        proofs.forEach((id, proof) -> result.put(id.toString(), proof.status() + ":" + proof.contract()));
        return result;
    }

    private static Map<String, String> combinedShape(CompilationArtifact artifact, BridgeRootSet roots,
            BridgeProof<BridgeRootRetentionContract> ownership) {
        return ownership.status() == BridgeProof.Status.PROVED
                ? shape(BridgeStringResults.proveForRoots(artifact, roots, ownership.contract().orElseThrow()))
                : Map.of("ownership", ownership.status() + ":" + ownership.reason());
    }

    private static void parity(SourceFile source, Map<String, String> expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge root String ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("strings.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("objectstrings"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                var roots = roots(artifact);
                var ownership = BridgeRootRetentionAnalyzer.analyze(artifact, roots);
                check(combinedShape(artifact, roots, ownership).equals(expected),
                        "String ownership changed after reconstruction: " + input);
                denied(() -> BridgeEntryModule.rootObjects(artifact, roots), "String conversion lowering");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void denied(Runnable action, String reason) {
        try { action.run(); }
        catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(reason), expected.toString());
            return;
        }
        throw new AssertionError("incomplete or stale String conversion admitted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
