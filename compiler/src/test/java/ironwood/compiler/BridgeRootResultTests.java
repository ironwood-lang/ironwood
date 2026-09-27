// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRootRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Set;

final class BridgeRootResultTests {
    private BridgeRootResultTests() {}

    static final Set<String> METHODS = Set.of("alias", "helper", "argument", "choose", "fresh", "empty");

    static BridgeRootSet roots(CompilationArtifact artifact, Set<String> methods, Set<String> constructors) {
        var program = artifact.program().orElseThrow();
        return BridgeRootSet.resolve(program, program.functions().stream().filter(function ->
                function.kind() == IrCallableKind.CONSTRUCTOR && constructors.contains(function.ownerClass())
                        || function.kind() == IrCallableKind.METHOD && function.ownerClass().startsWith("resultfixture.")
                        && methods.contains(function.sourceName())).map(BridgeCallableId::of).toList());
    }

    static BridgeEntryModule module(CompilationArtifact artifact) {
        return BridgeEntryModule.rootObjects(artifact, roots(artifact, METHODS, Set.of("resultfixture.Node")));
    }

    static void admission() {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(
                List.of(SourceFile.of("test/Results.iron", BridgeResultOriginTests.SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var module = module(artifact);
        check(module.rootRetention().orElseThrow().resultOrigins().size() == METHODS.size(), "result contracts lost");
        for (var entry : module.entries()) {
            if (!entry.root().callable().result().isReference()) continue;
            var stores = entry.function().blocks().stream().filter(block -> block.label().equals("failure.before"))
                    .flatMap(block -> block.instructions().stream()).filter(IrBridgeResultStoreInstruction.class::isInstance)
                    .map(IrBridgeResultStoreInstruction.class::cast).toList();
            check(stores.stream().anyMatch(store -> store.slot() == IrBridgeResultStoreInstruction.Slot.VALUE
                    && store.value() instanceof IrNull), "failed reference result can retain a stale frame pointer");
        }
        for (String name : List.of("mixed", "escapes", "unknown", "view", "ambiguous", "element")) {
            var proof = BridgeRootRetentionAnalyzer.analyze(artifact, roots(artifact, Set.of(name), Set.of("resultfixture.Node")));
            check(proof.status() != BridgeProof.Status.PROVED, "incomplete ownership surface admitted: " + name);
        }
        String retaining = BridgeResultOriginTests.SOURCE.replace("Node alias(boolean absent)",
                "Leaf retained; void retain(Leaf item) { retained = item; } Node alias(boolean absent)");
        var retained = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("test/Retaining.iron", retaining)));
        check(retained.valid(), retained.diagnostics().toString());
        var denied = BridgeRootRetentionAnalyzer.analyze(retained,
                roots(retained, Set.of("fresh", "retain"), Set.of("resultfixture.Node", "resultfixture.Leaf")));
        check(denied.status() != BridgeProof.Status.PROVED && denied.reason().contains("initial slot reporting"),
                "fresh root requiring unimplemented initial slot reporting admitted: " + denied);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
