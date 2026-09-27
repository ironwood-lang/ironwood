// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds root-origin and repeated-call acyclicity proofs to reference-store attribution. */
public final class BridgeRootRetentionAnalyzer {
    private BridgeRootRetentionAnalyzer() {}

    public static BridgeProof<BridgeRootRetentionContract> analyze(CompilationArtifact artifact, BridgeRootSet requested) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()) {
            return BridgeProof.unknown("root retention requires successful final bridge semantic facts");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var roots = requested.revalidate(program);
        if (!facts.matches(program) || !roots.resolved()) {
            return BridgeProof.unknown("root retention requires matching final facts and resolved roots");
        }
        var targets = new BridgeCallTargets(program);
        Set<IrType> rootTypes = new LinkedHashSet<>();
        Map<BridgeCallableId, BridgeResultOriginContract> results = new LinkedHashMap<>();
        for (var root : roots.roots()) {
            var callable = root.callable();
            if (callable.kind() == IrCallableKind.CONSTRUCTOR) {
                if (!facts.isConstructibleConstructor(callable)) {
                    return BridgeProof.rejected("root construction requires a concrete non-enum class: " + callable.linkage());
                }
                var construction = facts.constructors().get(callable);
                if (construction == null || construction.status() != BridgeProof.Status.PROVED) {
                    return BridgeProof.unknown("unpublished construction is not proved: " + callable.linkage());
                }
                rootTypes.add(IrType.reference(callable.owner()));
            } else if (callable.kind() != IrCallableKind.METHOD) {
                return BridgeProof.rejected("constructor-origin surface does not admit non-method entries: "
                        + callable.linkage());
            } else if (callable.result().isReference()) {
                var proof = facts.resultOrigins().get(callable);
                if (proof == null) return BridgeProof.unknown("missing final result-origin facts: " + callable.linkage());
                if (proof.status() != BridgeProof.Status.PROVED) return failed(proof.status(), proof.reason());
                var result = proof.contract().orElseThrow();
                if (result.kind() == BridgeResultOriginContract.Kind.DEPENDENT_VIEW) {
                    return BridgeProof.rejected("dependent result requires owner-aware view admission: " + callable.linkage());
                }
                results.put(callable, result);
            }
        }
        if (rootTypes.isEmpty()) return BridgeProof.rejected("root surface requires a proved constructor");
        for (var result : results.values()) {
            if (!rootTypes.contains(result.callable().result())) {
                return BridgeProof.rejected("reference result has no exact constructed root type: " + result.callable().linkage());
            }
            for (int input : result.inputs()) {
                if (!result.callable().parameters().get(input).equals(result.callable().result())) {
                    return BridgeProof.rejected("result alias requires the same exact input root type: " + result.callable().linkage());
                }
            }
            if (result.kind() == BridgeResultOriginContract.Kind.FRESH_ROOT) {
                for (var entry : facts.constructors().entrySet()) {
                    if (entry.getKey().owner().equals(result.callable().result().referenceName())
                            && entry.getValue().status() != BridgeProof.Status.PROVED) {
                        return BridgeProof.unknown("fresh result construction is not uniformly confined: " + entry.getKey().linkage());
                    }
                }
            }
        }
        for (var type : rootTypes) {
            var dynamic = targets.dynamicTypes(type);
            if (dynamic.size() != 1 || !dynamic.getFirst().name().equals(type.referenceName())) {
                return BridgeProof.rejected("constructor-origin surface requires an exact dynamic root type: " + type.displayName());
            }
        }
        for (var root : roots.roots()) {
            for (var input : root.callable().parameters()) {
                if (input.isReference() && !rootTypes.contains(input)) {
                    return BridgeProof.rejected("reference input has no constructor-origin root proof: "
                            + input.displayName() + " at " + root.callable().linkage());
                }
            }
        }
        // Generated destruction is deliberately outside this invocation surface.
        // Source calls may not independently invalidate its Java-owned roots.
        for (var type : rootTypes) {
            var nonReclamation = BridgeNonReclamationAnalyzer.analyze(program, roots, type, facts);
            if (nonReclamation.status() != BridgeProof.Status.PROVED) {
                return failed(nonReclamation.status(), "source can invalidate root storage: " + nonReclamation.reason());
            }
        }
        var attribution = BridgeRetentionAnalyzer.analyze(program, roots, facts);
        Map<BridgeCallableId, BridgeRetentionContract> entries = new LinkedHashMap<>();
        Map<IrType, Set<IrType>> graph = new LinkedHashMap<>();
        Map<IrType, Set<IrField>> fields = new LinkedHashMap<>();
        for (var type : rootTypes) {
            graph.put(type, new LinkedHashSet<>());
            fields.put(type, new LinkedHashSet<>());
        }
        for (var root : roots.roots()) {
            var callable = root.callable();
            var proof = attribution.get(callable);
            if (proof.status() != BridgeProof.Status.PROVED) return failed(proof.status(), proof.reason());
            var contract = proof.contract().orElseThrow();
            entries.put(callable, contract);
            for (var slot : contract.slots()) {
                var holder = callable.parameters().get(slot.holderInput());
                if (!rootTypes.contains(holder) || !holder.referenceName().equals(slot.field().ownerClass())) {
                    return BridgeProof.rejected("retaining field is not on an exact constructed root: " + slot);
                }
                fields.get(holder).add(slot.field());
                for (int value : slot.valueInputs()) {
                    var retained = callable.parameters().get(value);
                    if (!rootTypes.contains(retained)) return BridgeProof.rejected("unproved retained root at " + slot);
                    graph.get(holder).add(retained);
                }
            }
        }
        if (cyclic(graph)) return BridgeProof.rejected("possible repeated-call retention cycle: " + graph);
        Map<IrType, List<IrField>> slots = new LinkedHashMap<>();
        fields.forEach((type, values) -> slots.put(type, values.stream()
                .sorted(Comparator.comparing(IrField::name)).toList()));
        for (var result : results.values()) {
            if (result.kind() == BridgeResultOriginContract.Kind.FRESH_ROOT && !slots.get(result.callable().result()).isEmpty()) {
                return BridgeProof.rejected("fresh method results require bounded initial slot reporting: " + result.callable().linkage());
            }
        }
        return BridgeProof.proved(new BridgeRootRetentionContract(program, roots, rootTypes, entries, slots, graph, results),
                "reference origins are proved and uniform; attributed root slots form an acyclic type graph");
    }

    private static boolean cyclic(Map<IrType, Set<IrType>> graph) {
        Map<IrType, Integer> incoming = new LinkedHashMap<>();
        graph.keySet().forEach(type -> incoming.put(type, 0));
        graph.values().forEach(targets -> targets.forEach(type -> incoming.merge(type, 1, Integer::sum)));
        var pending = new ArrayDeque<IrType>();
        incoming.forEach((type, count) -> { if (count == 0) pending.add(type); });
        int visited = 0;
        while (!pending.isEmpty()) {
            var type = pending.removeFirst();
            visited++;
            for (var target : graph.get(type)) {
                int count = incoming.get(target) - 1;
                incoming.put(target, count);
                if (count == 0) pending.add(target);
            }
        }
        return visited != graph.size();
    }

    private static BridgeProof<BridgeRootRetentionContract> failed(BridgeProof.Status status, String reason) {
        return status == BridgeProof.Status.REJECTED ? BridgeProof.rejected(reason) : BridgeProof.unknown(reason);
    }
}
