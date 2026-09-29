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
    private static final IrType STRING = IrType.reference("ironwood.lang.String");
    private BridgePermanentAnalyzer() {}

    public static BridgeProof<BridgePermanentContract> analyze(CompilationArtifact artifact, BridgeRootSet requested) {
        return analyze(artifact, requested, Optional.empty());
    }

    public static BridgeProof<BridgePermanentContract> analyze(CompilationArtifact artifact, BridgeRootSet requested,
            BridgeEnumConversions conversions) {
        return analyze(artifact, requested, Optional.of(conversions));
    }

    private static BridgeProof<BridgePermanentContract> analyze(CompilationArtifact artifact, BridgeRootSet requested,
            Optional<BridgeEnumConversions> conversions) {
        if (!artifact.valid() || artifact.program().isEmpty() || artifact.bridgeConstructionFacts().isEmpty()) {
            return BridgeProof.unknown("permanent entries require successful bridge semantic analysis");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var entries = requested.revalidate(program);
        if (!facts.matches(program) || !entries.resolved() || entries.roots().isEmpty()
                || conversions.isPresent() && !conversions.orElseThrow().matches(program, entries)) {
            return BridgeProof.unknown("permanent entries require matching final facts and resolved roots");
        }
        Set<IrType> types = new LinkedHashSet<>();
        conversions.ifPresent(mapping -> types.addAll(mapping.enumTypes()));
        var roots = conversions.isEmpty() ? entries : BridgeRootSet.resolve(program, java.util.stream.Stream.concat(
                entries.roots().stream().map(BridgeRootSet.Root::callable), conversions.orElseThrow().initializers().stream()).toList());
        var targets = new BridgeCallTargets(program);
        Map<BridgeCallableId, BridgeCleanupContract> rollbacks = new LinkedHashMap<>();
        for (var root : entries.roots()) {
            var id = root.callable();
            if (id.kind() != IrCallableKind.METHOD && id.kind() != IrCallableKind.CONSTRUCTOR) {
                return BridgeProof.rejected("permanent entry requires a method or constructor");
            }
            boolean exactEnumReceiver = conversions.map(mapping -> mapping.parameters().get(id).stream()
                    .anyMatch(parameter -> parameter.input() == 0 && !parameter.nullable())).orElse(false);
            if (id.kind() == IrCallableKind.METHOD && !facts.isStatic(id) && !facts.isFinal(id) && !exactEnumReceiver) {
                var receivers = id.parameters().isEmpty() ? List.<IrClass>of() : targets.dynamicTypes(id.parameters().getFirst());
                if (receivers.size() != 1 || !receivers.getFirst().name().equals(id.owner())) {
                    return BridgeProof.rejected("permanent entry requires proved direct receiver dispatch");
                }
            }
            types.addAll(id.parameters());
            types.add(id.result());
            if (id.parameters().stream().anyMatch(BridgeByteViews::view)) {
                var views = BridgeByteViews.analyze(artifact, id);
                if (views.status() != BridgeProof.Status.PROVED) return failure(views.status(), views.reason());
            }
            if (id.result().isArray() || id.parameters().stream().anyMatch(IrType::isArray)) {
                var arrays = BridgeArrayInputs.values(artifact, id);
                if (arrays.status() != BridgeProof.Status.PROVED) return failure(arrays.status(), arrays.reason());
            }
            for (int input = 0; input < id.parameters().size(); input++) {
                if (id.parameters().get(input).equals(STRING) && !(id.result().equals(STRING)
                        ? facts.borrowsThroughResult(id, input) : facts.borrowsInput(id, input))) {
                    return BridgeProof.rejected("copied String input cleanup is not proved: " + id);
                }
            }
            if (id.kind() == IrCallableKind.CONSTRUCTOR) {
                if (id.owner().equals(STRING.referenceName())) {
                    return BridgeProof.rejected("String is a copied value, not a permanent native facade");
                }
                if (!facts.isConstructibleConstructor(id)) return BridgeProof.rejected("constructor requires a concrete non-enum class");
                var rollback = BridgeCleanupAnalyzer.analyze(artifact, roots, IrType.reference(id.owner()), Optional.of(id));
                if (rollback.status() != BridgeProof.Status.PROVED) return failure(rollback.status(), rollback.reason());
                rollbacks.put(id, rollback.contract().orElseThrow());
            }
        }
        Map<IrType, BridgeNonReclamationContract> references = new LinkedHashMap<>();
        for (var type : Set.copyOf(types)) {
            if (!type.typeArguments().isEmpty()) types.add(BridgeGenericDomain.storage(type));
            types.addAll(facts.genericAlternatives(type, entries));
        }
        for (var type : types) {
            if (!type.isReference() || type.equals(STRING) || BridgeArrayInputs.primitiveArray(type) || BridgeByteViews.view(type)) continue;
            if (!type.isNominalReference() && !type.isTypeParameter()) {
                return BridgeProof.rejected("permanent entry does not admit array or generic conversion");
            }
            var proof = BridgeNonReclamationAnalyzer.analyze(program, roots, type, facts);
            if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
            references.put(type, proof.contract().orElseThrow());
        }
        var contract = new BridgePermanentContract(program, roots, references, rollbacks);
        if (types.contains(STRING)) {
            var confinement = BridgeRetentionAnalyzer.copiedStringInputs(program, roots, facts, contract);
            for (var root : entries.roots()) {
                var id = root.callable();
                if (!id.parameters().contains(STRING) && !id.result().equals(STRING)) continue;
                var proof = confinement.get(id);
                if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
            }
        }
        return BridgeProof.proved(contract,
                "all reference inputs/results exclude exposed-storage reclamation across the complete surface");
    }

    private static BridgeProof<BridgePermanentContract> failure(BridgeProof.Status status, String reason) {
        return status == BridgeProof.Status.REJECTED ? BridgeProof.rejected(reason) : BridgeProof.unknown(reason);
    }
}
