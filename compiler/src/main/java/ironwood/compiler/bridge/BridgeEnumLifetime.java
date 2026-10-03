// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Bound enum storage proof over mixed entries; grants no lifetime permission to other references. */
public final class BridgeEnumLifetime {
    private final BridgeEnumConversions conversions;
    private final BridgePermanentContract contract;

    private BridgeEnumLifetime(BridgeEnumConversions conversions, BridgePermanentContract contract) {
        this.conversions = conversions;
        this.contract = contract;
    }

    public BridgeEnumConversions conversions() { return conversions; }
    public BridgePermanentContract contract() { return contract; }

    public boolean matches(IrProgram program, BridgeRootSet entries) {
        return conversions.matches(program, entries) && contract.program().equals(program);
    }

    public static BridgeEnumLifetime prove(CompilationArtifact artifact, BridgeEnumConversions conversions) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()) {
            throw new IllegalArgumentException("enum lifetime requires final construction facts");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        if (!facts.matches(program) || !conversions.matches(program, conversions.entries())) {
            throw new IllegalArgumentException("enum lifetime facts do not match the program and entries");
        }
        var roots = new ArrayList<>(conversions.entries().roots().stream().map(BridgeRootSet.Root::callable).toList());
        roots.addAll(conversions.initializers());
        var closure = BridgeRootSet.resolve(program, roots);
        var types = new LinkedHashSet<>(conversions.enumTypes());
        // Exact native receiver types include constant-specific bodies, not just the declared enum.
        conversions.parameters().forEach((id, parameters) -> parameters.stream().filter(parameter -> !parameter.nullable())
                .forEach(parameter -> types.add(id.parameters().get(parameter.input()))));
        Map<IrType, BridgeNonReclamationContract> references = new LinkedHashMap<>();
        for (var type : types) {
            var proof = BridgeNonReclamationAnalyzer.analyze(program, closure, type, facts);
            if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
            references.put(type, proof.contract().orElseThrow());
        }
        return new BridgeEnumLifetime(conversions, new BridgePermanentContract(program, closure, references, Map.of()));
    }
}
