// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Reuses final source proofs and P0 retention origins without granting ordinary free. */
public final class BridgeStringResults {
    private BridgeStringResults() {}

    public static Map<BridgeCallableId, BridgeProof<BridgeStringResultContract>> prove(
            CompilationArtifact artifact, BridgeRootSet requested) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()) {
            throw new IllegalArgumentException("String results require successful bridge semantic analysis");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        if (!facts.matches(program)) throw new IllegalArgumentException("String result facts do not match program");
        var roots = requested.revalidate(program);
        if (!roots.resolved()) throw new IllegalArgumentException("String results require resolved roots");
        var retention = BridgeRetentionAnalyzer.analyze(program, roots, facts);
        var immortals = BridgeRetentionAnalyzer.immortalStringResults(program, roots);
        Map<BridgeCallableId, BridgeProof<BridgeStringResultContract>> result = new LinkedHashMap<>();
        for (var root : roots.roots()) {
            var id = root.callable();
            if (!id.result().equals(IrType.reference("ironwood.lang.String"))
                    || id.kind() != IrCallableKind.METHOD || !facts.isStatic(id)
                    || id.parameters().stream().anyMatch(type -> type.isReference() && !type.equals(id.result()))) {
                result.put(id, BridgeProof.rejected("copied String results require static scalar/String signatures"));
                continue;
            }
            boolean borrowed = true;
            for (int input = 0; input < id.parameters().size(); input++) {
                if (id.parameters().get(input).isReference() && !facts.borrowsThroughResult(id, input)) borrowed = false;
            }
            if (!borrowed) {
                result.put(id, BridgeProof.rejected("String input publication or invalidation prevents cleanup"));
                continue;
            }
            var effects = retention.get(id);
            if (effects.status() != BridgeProof.Status.PROVED || !effects.contract().orElseThrow().slots().isEmpty()) {
                result.put(id, BridgeProof.unknown("String result retention is not proved: " + effects.reason()));
                continue;
            }
            var origin = facts.resultOrigins().get(id);
            if (immortals.contains(id)) {
                result.put(id, proved(id, BridgeStringResultContract.Kind.IMMORTAL, Set.of()));
            } else if (origin != null && origin.status() == BridgeProof.Status.PROVED) {
                var contract = origin.contract().orElseThrow();
                result.put(id, switch (contract.kind()) {
                    case FRESH_ROOT -> proved(id, BridgeStringResultContract.Kind.FRESH, Set.of());
                    case INPUT_ALIAS -> proved(id, BridgeStringResultContract.Kind.INPUT_ALIAS, contract.inputs());
                    case NULL_ONLY -> proved(id, BridgeStringResultContract.Kind.IMMORTAL, Set.of());
                    case DEPENDENT_VIEW -> BridgeProof.rejected("dependent String storage lacks a copy-cleanup contract");
                });
            } else result.put(id, BridgeProof.unknown("String result ownership is not proved: "
                    + (origin == null ? "missing final origin facts" : origin.reason())));
        }
        return Map.copyOf(result);
    }

    private static BridgeProof<BridgeStringResultContract> proved(BridgeCallableId id,
            BridgeStringResultContract.Kind kind, Set<Integer> inputs) {
        return BridgeProof.proved(new BridgeStringResultContract(id, kind, inputs),
                "final result origin, return-only borrowing and complete retention proofs agree");
    }
}
