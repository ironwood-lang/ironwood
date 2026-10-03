// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bound proof for named enum input conversion. Tokens are never native ordinals or addresses. */
public final class BridgeEnumInputs {
    private final IrProgram program;
    private final BridgeRootSet roots;
    private final Map<IrType, List<BridgeEnumConstants.Constant>> constants;

    private BridgeEnumInputs(IrProgram program, BridgeRootSet roots, Map<IrType, List<BridgeEnumConstants.Constant>> constants) {
        this.program = program;
        this.roots = roots;
        this.constants = Map.copyOf(constants);
    }

    public Map<IrType, List<BridgeEnumConstants.Constant>> constants() { return constants; }

    public boolean matches(IrProgram candidate, BridgeRootSet requested) {
        return program.equals(candidate) && roots.equals(requested.revalidate(candidate));
    }

    /** Requires the complete named constant set and a nonnegative, unique token for each name. */
    public static BridgeEnumInputs prove(CompilationArtifact artifact, BridgeRootSet requested,
                                        Map<IrType, Map<String, Integer>> tokens) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()) {
            throw new IllegalArgumentException("enum conversion requires final semantic facts");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var roots = requested.revalidate(program);
        if (!facts.matches(program) || !roots.resolved()) {
            throw new IllegalArgumentException("enum conversion requires matching resolved roots and facts");
        }
        var required = roots.roots().stream().flatMap(root -> root.callable().parameters().stream())
                .filter(IrType::isReference).collect(java.util.stream.Collectors.toSet());
        if (required.isEmpty() || !required.equals(tokens.keySet())) {
            throw new IllegalArgumentException("enum metadata does not exactly cover reference inputs");
        }
        var mappings = BridgeEnumConstants.prove(artifact, tokens);
        if (mappings.constants().values().stream().anyMatch(List::isEmpty)) {
            throw new IllegalArgumentException("empty enum invocation requires production conversion");
        }
        List<BridgeCallableId> proofRoots = new ArrayList<>(roots.roots().stream().map(BridgeRootSet.Root::callable).toList());
        proofRoots.addAll(mappings.initializers());
        var expanded = BridgeRootSet.resolve(program, proofRoots);
        var retention = BridgeRetentionAnalyzer.analyze(program, expanded);
        for (var proof : retention.values()) {
            if (proof.status() != BridgeProof.Status.PROVED || !proof.contract().orElseThrow().slots().isEmpty()) {
                throw new IllegalArgumentException("enum entry retention is not proved: " + proof.reason());
            }
        }
        for (var type : required) {
            var proof = BridgeNonReclamationAnalyzer.analyze(program, expanded, type, facts);
            if (proof.status() != BridgeProof.Status.PROVED) {
                throw new IllegalArgumentException("enum non-reclamation is not proved: " + proof.reason());
            }
        }
        for (var root : roots.roots()) {
            var callable = root.callable();
            if (callable.kind() != IrCallableKind.METHOD || callable.result().isReference()
                    || (!facts.isStatic(callable) && !facts.isFinal(callable))) {
                throw new IllegalArgumentException("enum entry requires a static or final scalar-result method");
            }
        }
        return new BridgeEnumInputs(program, roots, mappings.constants());
    }
}
