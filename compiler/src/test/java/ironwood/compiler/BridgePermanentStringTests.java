// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgePermanentAnalyzer;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BridgePermanentStringTests {
    static final String NAME = "Java Bridge permanent String conversions preserve temporary confinement";
    private static final String SOURCE = """
            package permanentstrings;
            public final class Catalog {
                private final String label = new String("catalog");
                private static Catalog saved;
                private Catalog next;
                public Catalog() {}
                destructor { free label; }
                public String text() { saved = this; return label; }
                public String alias(String input) { saved = this; return input; }
                public String copied(String input) { saved = this; return new String(input); }
                public String literal() { saved = this; return "literal"; }
                public int remember(Catalog other, String input) {
                    next = other; publish(other); return input == null ? -1 : input.length();
                }
                private static void publish(Object other) { savedObject = other; }
                private static Object savedObject;
                private static Catalog[] published = new Catalog[1];
                public int array(Catalog other, String input) {
                    Catalog[] values = new Catalog[1]; values[0] = other; published = values; return input.length();
                }
                public static Catalog unknown() { return saved; }
            }
            """;

    private BridgePermanentStringTests() {}

    static void proofs() throws Exception {
        var source = SourceFile.of("Catalog.iron", SOURCE);
        for (var mode : UnfreedMode.values()) {
            var artifact = analyze(source, mode);
            var roots = roots(artifact);
            var proof = BridgePermanentAnalyzer.analyze(artifact, roots);
            check(proof.status() == BridgeProof.Status.PROVED, proof.toString());
            var contract = proof.contract().orElseThrow();
            check(contract.references().keySet().equals(java.util.Set.of(IrType.reference("permanentstrings.Catalog"))),
                    "copied String acquired permanent identity");
            var values = BridgeStringResults.proveForPermanent(artifact, roots, contract);
            check(values.size() == 4, values.toString());
            values.forEach((id, value) -> {
                check(value.status() == BridgeProof.Status.PROVED, id + ": " + value);
                var expected = switch (id.name()) {
                    case "text" -> BridgeStringResultContract.Kind.BORROWED;
                    case "alias" -> BridgeStringResultContract.Kind.INPUT_ALIAS;
                    case "copied" -> BridgeStringResultContract.Kind.FRESH;
                    default -> BridgeStringResultContract.Kind.IMMORTAL;
                };
                check(value.contract().orElseThrow().kind() == expected, value.toString());
            });
            var module = BridgeEntryModule.permanentObjects(artifact, roots);
            check(module.stringResults().size() == 4 && module.destructions().isEmpty() && module.rootRetention().isEmpty(),
                    "permanent conversion gained destruction or root state");
            new ironwood.compiler.backend.LlvmEmitter().emit(module);
            check(BridgeRetentionAnalyzer.analyze(artifact.program().orElseThrow(), roots,
                    artifact.bridgeConstructionFacts().orElseThrow()).values().stream()
                    .anyMatch(value -> value.status() != BridgeProof.Status.PROVED), "ordinary retention publication gate weakened");
            var changed = analyze(SourceFile.of("Catalog.iron", SOURCE.replace("-1", "-2")), mode);
            denied(() -> BridgeStringResults.proveForPermanent(changed, roots(changed), contract));
            var subset = BridgeRootSet.resolve(artifact.program().orElseThrow(), roots.roots().stream()
                    .filter(root -> !root.callable().name().equals("remember")).map(BridgeRootSet.Root::callable).toList());
            denied(() -> BridgeStringResults.proveForPermanent(artifact, subset, contract));
            denied(() -> BridgeRetentionAnalyzer.copiedStringInputs(artifact.program().orElseThrow(), subset,
                    artifact.bridgeConstructionFacts().orElseThrow(), contract));
            if (mode == UnfreedMode.OFF) parity(source, shape(artifact));
            for (String member : List.of(
                    "private String retained; public void capture(String input) { retained = input; }",
                    "private static String retained; public void capture(String input) { retained = input; }",
                    "private static String[] retained = new String[1]; public void capture(String input) { retained[0] = input; }",
                    "public void capture(String input) { publish(input); }",
                    "private static class Box { String value; } public void capture(String input) { Box box = new Box(); box.value = input; publish(box); }",
                    "public void capture(String input) { throw new IllegalArgumentException(input); }",
                    "public int unknownEffect(String input) { long ignored = System.nanoTime(); return input.length(); }",
                    "public String mixed(String input, boolean pick) { return pick ? new String(input) : input; }",
                    "private static String retained; public String unknownString() { return retained; }")) {
                var badSource = SourceFile.of("Catalog.iron", SOURCE.replace("public Catalog() {}", "public Catalog() {} " + member));
                var bad = analyze(badSource, mode);
                check(shape(bad).values().stream().anyMatch(value -> !value.startsWith("PROVED")), "unsafe conversion admitted: " + member);
                if (member.contains("void capture")) {
                    var badRoots = roots(bad);
                    var program = bad.program().orElseThrow();
                    var facts = bad.bridgeConstructionFacts().orElseThrow();
                    var type = IrType.reference("permanentstrings.Catalog");
                    var lifetime = BridgeNonReclamationAnalyzer.analyze(program, badRoots, type, facts);
                    check(lifetime.status() == BridgeProof.Status.PROVED, lifetime.toString());
                    // Bind real non-reclamation evidence directly to exercise the
                    // retention projection independently of the source borrow gate.
                    var storage = new BridgePermanentContract(program, badRoots,
                            Map.of(type, lifetime.contract().orElseThrow()), Map.of());
                    var confinement = BridgeRetentionAnalyzer.copiedStringInputs(program, badRoots, facts, storage);
                    check(confinement.entrySet().stream().filter(entry -> entry.getKey().name().equals("capture"))
                            .allMatch(entry -> entry.getValue().status() != BridgeProof.Status.PROVED),
                            "permanent storage hid captured String input: " + member);
                }
                denied(() -> BridgeEntryModule.permanentObjects(bad, roots(bad)));
                if (mode == UnfreedMode.OFF) parity(badSource, shape(bad));
            }
        }
    }

    private static CompilationArtifact analyze(SourceFile source, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics() + "\n" + source.content());
        return artifact;
    }

    private static BridgeRootSet roots(CompilationArtifact artifact) {
        var selected = BridgeExportSurface.concreteObjects(artifact, List.of("permanentstrings"));
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        return selected.surface().orElseThrow().roots();
    }

    private static Map<String, String> shape(CompilationArtifact artifact) {
        var roots = roots(artifact);
        var proof = BridgePermanentAnalyzer.analyze(artifact, roots);
        if (proof.status() != BridgeProof.Status.PROVED) return Map.of("permanent", proof.status() + ":" + proof.reason());
        var result = new TreeMap<String, String>();
        BridgeStringResults.proveForPermanent(artifact, roots, proof.contract().orElseThrow()).forEach((id, value) ->
                result.put(id.toString(), value.status() + ":" + value.contract() + ":" + value.reason()));
        return result;
    }

    private static void parity(SourceFile source, Map<String, String> expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge permanent String ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("strings.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("permanentstrings"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                check(shape(artifact).equals(expected), "permanent String proof changed after reconstruction: " + input);
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
        throw new AssertionError("unproved permanent String conversion admitted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
