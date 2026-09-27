// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reuses final source proofs and P0 retention origins without granting ordinary free. */
public final class BridgeStringResults {
    private BridgeStringResults() {}

    public static Map<BridgeCallableId, BridgeProof<BridgeStringResultContract>> prove(
            CompilationArtifact artifact, BridgeRootSet requested) {
        return prove(artifact, requested, Optional.empty(), Optional.empty());
    }

    /** Object getters copy borrowed storage while its proved native owner remains live. */
    public static Map<BridgeCallableId, BridgeProof<BridgeStringResultContract>> proveForRoots(
            CompilationArtifact artifact, BridgeRootSet requested, BridgeRootRetentionContract ownership) {
        if (!artifact.valid() || !ownership.matches(artifact.program().orElseThrow(), requested)) {
            throw new IllegalArgumentException("String object results require matching complete root ownership");
        }
        return prove(artifact, requested, Optional.of(ownership), Optional.empty());
    }

    public static Map<BridgeCallableId, BridgeProof<BridgeStringResultContract>> proveForPermanent(
            CompilationArtifact artifact, BridgeRootSet requested, BridgePermanentContract permanent) {
        if (!artifact.valid() || !permanent.matches(artifact.program().orElseThrow(), requested)) {
            throw new IllegalArgumentException("String object results require matching complete permanent ownership");
        }
        return prove(artifact, requested, Optional.empty(), Optional.of(permanent));
    }

    private static Map<BridgeCallableId, BridgeProof<BridgeStringResultContract>> prove(
            CompilationArtifact artifact, BridgeRootSet requested, Optional<BridgeRootRetentionContract> ownership,
            Optional<BridgePermanentContract> permanent) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()) {
            throw new IllegalArgumentException("String results require successful bridge semantic analysis");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        if (!facts.matches(program)) throw new IllegalArgumentException("String result facts do not match program");
        var roots = requested.revalidate(program);
        if (!roots.resolved()) throw new IllegalArgumentException("String results require resolved roots");
        var enumLifetime = ownership.flatMap(BridgeRootRetentionContract::enumLifetime);
        var permanentValues = ownership.flatMap(BridgeRootRetentionContract::permanentValues);
        var retention = permanentValues.isPresent()
                ? BridgeRetentionAnalyzer.withPermanentValues(program, permanentValues.orElseThrow().contract().roots(), facts, permanentValues.orElseThrow())
                : enumLifetime.isPresent()
                ? BridgeRetentionAnalyzer.withEnumValues(program, enumLifetime.orElseThrow().contract().roots(), facts, enumLifetime.orElseThrow())
                : permanent.isPresent()
                ? BridgeRetentionAnalyzer.copiedStringInputs(program, roots, facts, permanent.orElseThrow())
                : BridgeRetentionAnalyzer.analyze(program, roots, facts);
        boolean objects = ownership.isPresent() || permanent.isPresent();
        var immortals = BridgeRetentionAnalyzer.immortalStringResults(program, roots);
        var borrowedOnly = objects ? BridgeRetentionAnalyzer.borrowedStringResults(program, roots) : Set.<BridgeCallableId>of();
        Map<BridgeCallableId, BridgeProof<BridgeStringResultContract>> result = new LinkedHashMap<>();
        for (var root : roots.roots()) {
            var id = root.callable();
            if (objects && !id.result().equals(IrType.reference("ironwood.lang.String"))) continue;
            if (!id.result().equals(IrType.reference("ironwood.lang.String"))
                    || id.kind() != IrCallableKind.METHOD || !objects && (!facts.isStatic(id)
                    || id.parameters().stream().anyMatch(type -> type.isReference() && !type.equals(id.result())))) {
                result.put(id, BridgeProof.rejected("copied String results require static scalar/String signatures"));
                continue;
            }
            boolean borrowed = true;
            for (int input = 0; input < id.parameters().size(); input++) {
                if (id.parameters().get(input).equals(id.result()) && !facts.borrowsThroughResult(id, input)) borrowed = false;
            }
            if (!borrowed) {
                result.put(id, BridgeProof.rejected("String input publication or invalidation prevents cleanup"));
                continue;
            }
            var effects = retention.get(id);
            if (effects.status() != BridgeProof.Status.PROVED || (ownership.isEmpty()
                    ? !effects.contract().orElseThrow().slots().isEmpty()
                    : !effects.contract().orElseThrow().equals(ownership.orElseThrow().entries().get(id)))) {
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
                    case DEPENDENT_VIEW -> borrowed(ownership, permanent, contract);
                });
            } else if (!facts.isStatic(id) && !id.parameters().contains(id.result()) && facts.borrowsThroughResult(id, 0)
                    && borrowedOnly.contains(id) && (permanent.map(value -> value.references().containsKey(id.parameters().getFirst())).orElse(false)
                    || ownership.map(value -> value.permanentReferences().containsKey(id.parameters().getFirst())).orElse(false))) {
                // A permanent receiver's non-fresh result remains live through immediate copying.
                // Exclude copied String inputs: those need explicit surviving-alias cleanup.
                result.put(id, proved(id, BridgeStringResultContract.Kind.BORROWED, Set.of(0)));
            } else result.put(id, BridgeProof.unknown("String result ownership is not proved: "
                    + (origin == null ? "missing final origin facts" : origin.reason())));
        }
        return Map.copyOf(result);
    }

    private static BridgeProof<BridgeStringResultContract> borrowed(Optional<BridgeRootRetentionContract> ownership,
            Optional<BridgePermanentContract> permanent, BridgeResultOriginContract origin) {
        if (permanent.isPresent() && origin.inputs().size() == 1
                && permanent.orElseThrow().references().containsKey(
                        origin.callable().parameters().get(origin.inputs().iterator().next()))) {
            return proved(origin.callable(), BridgeStringResultContract.Kind.BORROWED, origin.inputs());
        }
        if (ownership.isPresent() && origin.inputs().size() == 1) {
            var input = origin.callable().parameters().get(origin.inputs().iterator().next());
            var roots = ownership.orElseThrow();
            if (roots.constructedRootTypes().contains(input) || roots.borrowedResultTypes().contains(input)
                    || roots.permanentReferences().containsKey(input)) {
                return proved(origin.callable(), BridgeStringResultContract.Kind.BORROWED, origin.inputs());
            }
        }
        return BridgeProof.rejected("dependent String storage lacks a proved live owner for copying");
    }

    private static BridgeProof<BridgeStringResultContract> proved(BridgeCallableId id,
            BridgeStringResultContract.Kind kind, Set<Integer> inputs) {
        return BridgeProof.proved(new BridgeStringResultContract(id, kind, inputs),
                "final result origin, return-only borrowing and complete retention proofs agree");
    }
}
