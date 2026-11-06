// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.source.SourceFile;
import java.util.List;

/** Managed construction must not accidentally certify an empty legacy rollback. */
final class Gc7RetirementTests {
    static final String NAME = "GC7 managed construction never grants legacy rollback capabilities";

    private Gc7RetirementTests() {}

    static void construction() {
        var artifact = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Graph.iron", """
                package gc7;
                public final class Graph {
                    private Object child;
                    public Graph() { this.child = new Object(); }
                    public Graph alias() { return this; }
                    public void share(Graph other) { this.child = other.child; }
                }
                """)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        check(facts.matches(artifact.program().orElseThrow()), "construction facts lost their program identity");
        check(!facts.constructors().isEmpty(), "missing constructor refusal inventory");
        check(facts.constructors().values().stream().allMatch(proof -> proof.status() == BridgeProof.Status.UNKNOWN),
                "retired ownership facts granted a legacy rollback capability");
        var managed = BridgeObjectAdmission.proveManaged(artifact, List.of("gc7"));
        check(managed.status() == BridgeProof.Status.PROVED, managed.reason());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
