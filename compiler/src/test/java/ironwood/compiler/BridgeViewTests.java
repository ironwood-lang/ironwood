// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRootRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Set;

final class BridgeViewTests {
    private BridgeViewTests() {}

    static final String SOURCE = BridgeResultOriginTests.SOURCE.replace("int number;",
            "int number; int value() { return number; } Leaf self() { return this; }");
    static final Set<String> METHODS = Set.of("view", "viewOf", "value", "self", "fresh", "alias");
    static final Set<String> TYPES = Set.of("resultfixture.Node", "resultfixture.Leaf");

    static BridgeEntryModule module(CompilationArtifact artifact) {
        return BridgeEntryModule.rootObjects(artifact, BridgeRootResultTests.roots(artifact, METHODS, TYPES));
    }

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            var sources = List.of(SourceFile.of("test/Views.iron", SOURCE));
            var ordinary = new CompilerPipeline(mode).analyze(sources);
            var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
            check(ordinary.valid() && artifact.valid() && ordinary.diagnostics().equals(artifact.diagnostics()), artifact.diagnostics().toString());
            var module = module(artifact);
            var contract = module.rootRetention().orElseThrow();
            check(contract.borrowedResultTypes().equals(Set.of(IrType.reference("resultfixture.Leaf"))), "missing child-origin type");
            check(module.destructions().size() == 2, "owned instances of the view class lost their free capability");
            var onlyViews = BridgeEntryModule.rootObjects(artifact, BridgeRootResultTests.roots(artifact, METHODS, Set.of("resultfixture.Node")));
            check(onlyViews.destructions().size() == 1 && onlyViews.destructions().getFirst().contract().type().referenceName().equals("resultfixture.Node"),
                    "borrowed-only storage acquired an independent destruction entry");
            var childSlots = SOURCE.replace("int number;", "Node retained; void retain(Node node) { retained = node; } int number;");
            var negative = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("test/ChildSlots.iron", childSlots)));
            check(negative.valid(), negative.diagnostics().toString());
            var denied = BridgeRootRetentionAnalyzer.analyze(negative, BridgeRootResultTests.roots(negative, Set.of("view", "retain"), TYPES));
            check(denied.status() == BridgeProof.Status.REJECTED && denied.reason().contains("not on an exact constructed root"),
                    "child-held slot admitted: " + denied);
            var retaining = SOURCE.replace("Node alias(boolean absent)",
                    "Leaf retained; void retainView(Leaf leaf) { retained = leaf; } Node alias(boolean absent)");
            negative = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("test/RetainedView.iron", retaining)));
            check(negative.valid(), negative.diagnostics().toString());
            denied = BridgeRootRetentionAnalyzer.analyze(negative, BridgeRootResultTests.roots(negative, Set.of("view", "retainView"), TYPES));
            check(denied.status() == BridgeProof.Status.REJECTED && denied.reason().contains("cycle"),
                    "view retention admitted a possible self-owner cycle: " + denied.reason());
            var nested = SOURCE.replace("int number;", "private final Sprout child = new Sprout(); "
                    + "destructor { free child; } Sprout sprout() { return child; } int number;") + "\nfinal class Sprout {}";
            negative = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("test/Nested.iron", nested)));
            check(negative.valid(), negative.diagnostics().toString());
            denied = BridgeRootRetentionAnalyzer.analyze(negative, BridgeRootResultTests.roots(negative, Set.of("view", "sprout"), TYPES));
            check(denied.status() == BridgeProof.Status.REJECTED && denied.reason().contains("independent root input"),
                    "nested view owner admitted without root propagation: " + denied);
        }
        BridgeResultOriginTests.artifacts(SOURCE);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
