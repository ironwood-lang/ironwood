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
import java.util.Set;
import java.util.TreeMap;

final class BridgeViewRetentionTests {
    static final String NAME = "Java Bridge retained views preserve independent owner alternatives and cycle rejection";
    private static final String SOURCE = """
            package retainedviews;
            final class Leaf { int number; Leaf() {} Leaf self() { return this; } }
            final class Node {
                private final Leaf child = new Leaf();
                Node() {} destructor { free child; }
                Leaf view() { return child; }
            }
            final class Other {
                private final Leaf child = new Leaf();
                Other() {} destructor { free child; }
                Leaf view() { return child; }
            }
            final class Keeper {
                Leaf retained;
                Keeper() {}
                void set(Leaf value) { retained = value; }
                void clear() { retained = null; }
                void setThenFail(Leaf value) { retained = value; throw null; }
                static void helper(Keeper holder, Leaf value) { holder.retained = value; }
                void other(Keeper holder, Leaf value) { helper(holder, value); }
            }
            """;
    private static final Set<String> METHODS = Set.of("view", "self", "set", "clear", "setThenFail", "other");
    private static final Set<String> CONSTRUCTORS = Set.of("retainedviews.Node", "retainedviews.Other", "retainedviews.Keeper");

    private BridgeViewRetentionTests() {}

    static void proofs() throws Exception {
        var source = SourceFile.of("Views.iron", SOURCE);
        for (var mode : UnfreedMode.values()) {
            var artifact = analyze(source, mode);
            for (boolean standalone : List.of(false, true)) {
                var roots = roots(artifact, METHODS, standalone);
                var proof = BridgeRootRetentionAnalyzer.analyze(artifact, roots);
                check(proof.status() == BridgeProof.Status.PROVED, proof.status() + ":" + proof.reason());
                var contract = proof.contract().orElseThrow();
                var leaf = IrType.reference("retainedviews.Leaf");
                var keeper = IrType.reference("retainedviews.Keeper");
                var expected = new java.util.HashSet<>(Set.of(IrType.reference("retainedviews.Node"), IrType.reference("retainedviews.Other")));
                if (standalone) expected.add(leaf);
                check(contract.rootOwnerTypes().get(leaf).equals(expected), "lost possible owner of retained view");
                check(contract.dependencies().get(keeper).equals(expected), "dependency graph follows view storage instead of its root");
                check(contract.rootOwnerTypes().get(keeper).equals(Set.of(keeper)), "independent holder lost its own root identity");
                var module = BridgeEntryModule.rootObjects(artifact, roots);
                check(module.destructions().size() == (standalone ? 4 : 3), "borrowed views gained or lost destruction permission");
                for (var entry : contract.entries().entrySet()) {
                    if (!Set.of("set", "setThenFail", "other").contains(entry.getKey().name())) continue;
                    check(entry.getValue().slots().size() == 1 && !entry.getValue().slots().getFirst().valueInputs().isEmpty(),
                            "view store lost normal/exceptional input attribution");
                }
                new ironwood.compiler.backend.LlvmEmitter().emit(NativeLinkPipeline.finish(NativeLinkPipeline.optimize(module.program())));
                if (mode == UnfreedMode.OFF) parity(source, METHODS, standalone, shape(contract));
                try { contract.rootOwnerTypes().get(leaf).clear(); throw new AssertionError("mutable owner alternatives"); }
                catch (UnsupportedOperationException expectedFailure) { /* Immutable proof evidence. */ }
            }
            var noOwners = BridgeRootRetentionAnalyzer.analyze(artifact, roots(artifact,
                    Set.of("set", "clear", "setThenFail", "other"), false));
            check(noOwners.status() != BridgeProof.Status.PROVED, "retained value with no exposed owner origin admitted");
            for (String negative : List.of(
                    SOURCE.replace("Node() {}", "Node() {} Keeper retained; void cycle(Keeper value) { retained = value; }"),
                    SOURCE.replace("Leaf self()", "Keeper retained; void cycle(Keeper value) { retained = value; } Leaf self()"),
                    SOURCE.replace("void clear()", "void copy(Keeper value) { retained = value.retained; } void clear()"))) {
                var input = SourceFile.of("Views.iron", negative);
                var bad = analyze(input, mode);
                var methods = new java.util.HashSet<>(METHODS);
                methods.addAll(Set.of("cycle", "copy"));
                var rejected = BridgeRootRetentionAnalyzer.analyze(bad, roots(bad, methods, true));
                check(rejected.status() != BridgeProof.Status.PROVED, "cycle/child holder/slot transfer admitted");
                if (negative.contains("Node() {} Keeper")) check(rejected.reason().contains("cycle"), rejected.reason());
                if (negative.contains("Keeper retained; void cycle") && !negative.contains("Node() {} Keeper")) {
                    check(rejected.reason().contains("not on an exact constructed root"), rejected.reason());
                }
                if (mode == UnfreedMode.OFF) parity(input, methods, true,
                        java.util.Map.of("rejected", rejected.status() + ":" + rejected.reason()));
            }
        }
    }

    private static CompilationArtifact analyze(SourceFile source, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static BridgeRootSet roots(CompilationArtifact artifact, Set<String> methods, boolean standalone) {
        var program = artifact.program().orElseThrow();
        return BridgeRootSet.resolve(program, program.functions().stream().filter(function ->
                function.ownerClass().startsWith("retainedviews.") && (function.kind() == IrCallableKind.METHOD
                && methods.contains(function.sourceName()) || function.kind() == IrCallableKind.CONSTRUCTOR
                && (CONSTRUCTORS.contains(function.ownerClass()) || standalone && function.ownerClass().equals("retainedviews.Leaf"))))
                .map(BridgeCallableId::of).toList());
    }

    private static java.util.Map<String, String> shape(BridgeRootRetentionContract contract) {
        var result = new TreeMap<String, String>();
        contract.rootOwnerTypes().forEach((type, owners) -> result.put("owner:" + type.displayName(), owners.stream()
                .map(IrType::displayName).sorted().toList().toString()));
        contract.dependencies().forEach((type, owners) -> result.put("edge:" + type.displayName(), owners.stream()
                .map(IrType::displayName).sorted().toList().toString()));
        contract.entries().forEach((id, entry) -> result.put("entry:" + id, entry.toString()));
        return result;
    }

    private static void parity(SourceFile source, Set<String> methods, boolean standalone, java.util.Map<String, String> expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge retained views ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("views.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).load(List.of(), CONSTRUCTORS.stream().sorted().toList());
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                var proof = BridgeRootRetentionAnalyzer.analyze(artifact, roots(artifact, methods, standalone));
                var actual = proof.status() == BridgeProof.Status.PROVED ? shape(proof.contract().orElseThrow())
                        : java.util.Map.of("rejected", proof.status() + ":" + proof.reason());
                check(actual.equals(expected),
                        "view-owner alternatives changed after reconstruction: " + input);
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
