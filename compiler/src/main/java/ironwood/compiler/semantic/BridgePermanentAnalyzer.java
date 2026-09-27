// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** D192 admission across the complete export surface, independent of return ownership. */
public final class BridgePermanentAnalyzer {
    private BridgePermanentAnalyzer() {}

    public static BridgeProof<BridgePermanentContract> analyze(CompilationArtifact artifact, BridgeRootSet requested) {
        if (!artifact.valid() || artifact.program().isEmpty() || artifact.bridgeConstructionFacts().isEmpty()) {
            return BridgeProof.unknown("permanent entries require successful bridge semantic analysis");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var roots = requested.revalidate(program);
        if (!facts.matches(program) || !roots.resolved() || roots.roots().isEmpty()) {
            return BridgeProof.unknown("permanent entries require matching final facts and resolved roots");
        }
        Set<IrType> types = new LinkedHashSet<>();
        var targets = new BridgeCallTargets(program);
        Map<BridgeCallableId, BridgeCleanupContract> rollbacks = new LinkedHashMap<>();
        for (var root : roots.roots()) {
            var id = root.callable();
            if (id.kind() != IrCallableKind.METHOD && id.kind() != IrCallableKind.CONSTRUCTOR) {
                return BridgeProof.rejected("permanent entry requires a method or constructor");
            }
            if (id.kind() == IrCallableKind.METHOD && !facts.isStatic(id) && !facts.isFinal(id)) {
                var receivers = id.parameters().isEmpty() ? List.<IrClass>of() : targets.dynamicTypes(id.parameters().getFirst());
                if (receivers.size() != 1 || !receivers.getFirst().name().equals(id.owner())) {
                    return BridgeProof.rejected("permanent entry requires proved direct receiver dispatch");
                }
            }
            types.addAll(id.parameters());
            types.add(id.result());
            if (id.kind() == IrCallableKind.CONSTRUCTOR) {
                if (!facts.isConstructibleConstructor(id)) return BridgeProof.rejected("constructor requires a concrete non-enum class");
                var rollback = BridgeCleanupAnalyzer.analyze(artifact, roots, IrType.reference(id.owner()), Optional.of(id));
                if (rollback.status() != BridgeProof.Status.PROVED) return failure(rollback.status(), rollback.reason());
                rollbacks.put(id, rollback.contract().orElseThrow());
            }
        }
        Map<IrType, BridgeNonReclamationContract> references = new LinkedHashMap<>();
        for (var type : types) {
            if (!type.isReference()) continue;
            if (!type.isNominalReference() || !type.typeArguments().isEmpty()
                    || type.referenceName().equals("ironwood.lang.String")) {
                return BridgeProof.rejected("permanent entry does not admit array, generic or copied String conversion");
            }
            var proof = BridgeNonReclamationAnalyzer.analyze(program, roots, type, facts);
            if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
            references.put(type, proof.contract().orElseThrow());
        }
        return BridgeProof.proved(new BridgePermanentContract(program, roots, references, rollbacks),
                "all reference inputs/results exclude exposed-storage reclamation across the complete surface");
    }

    private static BridgeProof<BridgePermanentContract> failure(BridgeProof.Status status, String reason) {
        return status == BridgeProof.Status.REJECTED ? BridgeProof.rejected(reason) : BridgeProof.unknown(reason);
    }
}
