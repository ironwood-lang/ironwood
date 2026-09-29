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
import java.util.Optional;
import java.util.Set;

/** Adds root-origin and repeated-call acyclicity proofs to reference-store attribution. */
public final class BridgeRootRetentionAnalyzer {
    private static final IrType STRING = IrType.reference("ironwood.lang.String");
    private BridgeRootRetentionAnalyzer() {}

    public static BridgeProof<BridgeRootRetentionContract> analyze(CompilationArtifact artifact, BridgeRootSet requested) {
        return analyze(artifact, requested, Optional.empty(), Optional.empty());
    }

    public static BridgeProof<BridgeRootRetentionContract> analyze(CompilationArtifact artifact, BridgeRootSet requested,
            BridgeEnumConversions conversions) {
        return analyze(artifact, requested, Optional.of(conversions), Optional.empty());
    }

    public static BridgeProof<BridgeRootRetentionContract> analyze(CompilationArtifact artifact, BridgeRootSet requested,
            BridgePermanentValues permanent) {
        return analyze(artifact, requested, permanent.enums().map(BridgeEnumLifetime::conversions), Optional.of(permanent));
    }

    private static BridgeProof<BridgeRootRetentionContract> analyze(CompilationArtifact artifact, BridgeRootSet requested,
            Optional<BridgeEnumConversions> conversions, Optional<BridgePermanentValues> permanent) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()) {
            return BridgeProof.unknown("root retention requires successful final bridge semantic facts");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var roots = requested.revalidate(program);
        if (!facts.matches(program) || !roots.resolved()) {
            return BridgeProof.unknown("root retention requires matching final facts and resolved roots");
        }
        if (conversions.isPresent() && !conversions.orElseThrow().matches(program, roots)) {
            return BridgeProof.unknown("enum conversions do not match root retention entries");
        }
        if (permanent.isPresent() && !permanent.orElseThrow().matches(program, roots)) {
            return BridgeProof.unknown("permanent values do not match root retention entries");
        }
        Optional<BridgeEnumLifetime> enumLifetime;
        try { enumLifetime = permanent.isPresent() ? permanent.orElseThrow().enums()
                : conversions.map(value -> BridgeEnumLifetime.prove(artifact, value)); }
        catch (IllegalArgumentException failure) { return BridgeProof.unknown(failure.getMessage()); }
        var lifetime = permanent.map(BridgePermanentValues::contract)
                .or(() -> enumLifetime.map(BridgeEnumLifetime::contract));
        var analysisRoots = lifetime.map(BridgePermanentContract::roots).orElse(roots);
        var permanentTypes = lifetime.map(value -> value.references().keySet()).orElse(Set.of());
        var targets = new BridgeCallTargets(program);
        Set<IrType> rootTypes = new LinkedHashSet<>();
        Set<IrType> borrowedTypes = new LinkedHashSet<>();
        Map<BridgeCallableId, BridgeResultOriginContract> results = new LinkedHashMap<>();
        for (var root : roots.roots()) {
            var callable = root.callable();
            if (callable.parameters().stream().anyMatch(BridgeByteViews::view)) {
                var views = BridgeByteViews.analyze(artifact, callable);
                if (views.status() != BridgeProof.Status.PROVED) return failed(views.status(), views.reason());
            }
            if (callable.result().isArray() || callable.parameters().stream().anyMatch(IrType::isArray)) {
                var arrays = BridgeArrayInputs.values(artifact, callable);
                if (arrays.status() != BridgeProof.Status.PROVED) return failed(arrays.status(), arrays.reason());
            }
            if (callable.kind() == IrCallableKind.CONSTRUCTOR) {
                if (callable.owner().equals(STRING.referenceName())) {
                    return BridgeProof.rejected("String is a copied value, not a constructed native root facade");
                }
                if (!facts.isConstructibleConstructor(callable)) {
                    return BridgeProof.rejected("root construction requires a concrete non-enum class: " + callable.linkage());
                }
                var construction = facts.constructors().get(callable);
                if (construction == null || construction.status() != BridgeProof.Status.PROVED) {
                    return BridgeProof.unknown("unpublished construction is not proved: " + callable.linkage());
                }
                if (!permanentTypes.contains(IrType.reference(callable.owner()))) rootTypes.add(IrType.reference(callable.owner()));
            } else if (callable.kind() != IrCallableKind.METHOD) {
                return BridgeProof.rejected("constructor-origin surface does not admit non-method entries: "
                        + callable.linkage());
            } else if (callable.result().isReference() && !callable.result().equals(STRING)
                    && !BridgeArrayInputs.primitiveArray(callable.result()) && !permanentTypes.contains(callable.result())) {
                var proof = facts.resultOrigins().get(callable);
                if (proof == null) return BridgeProof.unknown("missing final result-origin facts: " + callable.linkage());
                if (proof.status() != BridgeProof.Status.PROVED) return failed(proof.status(), proof.reason());
                var result = proof.contract().orElseThrow();
                if (result.kind() == BridgeResultOriginContract.Kind.DEPENDENT_VIEW) {
                    borrowedTypes.add(BridgeGenericDomain.storage(callable.result()));
                }
                if (result.kind() == BridgeResultOriginContract.Kind.FRESH_ROOT && !callable.result().typeArguments().isEmpty()) {
                    var constructors = facts.constructors().entrySet().stream()
                            .filter(entry -> entry.getKey().owner().equals(callable.result().referenceName())).toList();
                    if (constructors.isEmpty() || constructors.stream().anyMatch(entry -> entry.getValue().status() != BridgeProof.Status.PROVED)) {
                        return BridgeProof.unknown("generic factory construction is not uniformly confined: " + callable.linkage());
                    }
                    rootTypes.add(BridgeGenericDomain.storage(callable.result()));
                }
                results.put(callable, result);
            }
        }
        if (rootTypes.isEmpty()) return BridgeProof.rejected("root surface requires a proved constructor");
        Set<IrType> referenceTypes = new LinkedHashSet<>(rootTypes);
        referenceTypes.addAll(borrowedTypes);
        Map<IrType, Set<IrType>> owners = new LinkedHashMap<>();
        rootTypes.forEach(type -> owners.put(type, new LinkedHashSet<>(Set.of(type))));
        for (var result : results.values()) {
            if (result.kind() == BridgeResultOriginContract.Kind.DEPENDENT_VIEW) {
                var owner = BridgeGenericDomain.storage(result.callable().parameters().get(result.inputs().iterator().next()));
                if (!rootTypes.contains(owner) || borrowedTypes.contains(owner)) {
                    return BridgeProof.rejected("dependent view requires one exact independent root input: " + result.callable().linkage());
                }
                owners.computeIfAbsent(BridgeGenericDomain.storage(result.callable().result()), ignored -> new LinkedHashSet<>()).add(owner);
                continue;
            }
            if (!referenceTypes.contains(BridgeGenericDomain.storage(result.callable().result()))) {
                return BridgeProof.rejected("reference result has no exact constructed root type: " + result.callable().linkage());
            }
            for (int input : result.inputs()) {
                if (!BridgeGenericDomain.storage(result.callable().parameters().get(input)).equals(BridgeGenericDomain.storage(result.callable().result()))) {
                    return BridgeProof.rejected("result alias requires the same exact input root type: " + result.callable().linkage());
                }
            }
            if (result.kind() == BridgeResultOriginContract.Kind.FRESH_ROOT) {
                if (!rootTypes.contains(BridgeGenericDomain.storage(result.callable().result()))) {
                    return BridgeProof.rejected("fresh result requires a proved root construction capability: " + result.callable().linkage());
                }
                for (var entry : facts.constructors().entrySet()) {
                    if (entry.getKey().owner().equals(result.callable().result().referenceName())
                            && entry.getValue().status() != BridgeProof.Status.PROVED) {
                        return BridgeProof.unknown("fresh result construction is not uniformly confined: " + entry.getKey().linkage());
                    }
                }
            }
        }
        for (var type : referenceTypes) {
            var dynamic = targets.dynamicTypes(type);
            if (dynamic.size() != 1 || !dynamic.getFirst().name().equals(type.referenceName())) {
                return BridgeProof.rejected("constructor-origin surface requires an exact dynamic root type: " + type.displayName());
            }
        }
        for (var root : roots.roots()) {
            var callable = root.callable();
            for (int index = 0; index < callable.parameters().size(); index++) {
                var input = callable.parameters().get(index);
                if (BridgeArrayInputs.primitiveArray(input) || BridgeByteViews.view(input)) continue; // Separately proved call-scoped copy above.
                if (input.equals(STRING)) {
                    boolean confined = callable.result().equals(STRING)
                            ? facts.borrowsThroughResult(callable, index) : facts.borrowsInput(callable, index);
                    if (!confined) return BridgeProof.rejected("copied String input cleanup is not proved: "
                            + callable.linkage() + " parameter " + index);
                    continue;
                }
                if (input.isReference() && !referenceTypes.contains(BridgeGenericDomain.storage(input)) && !permanentTypes.contains(input)) {
                    return BridgeProof.rejected("reference input has no constructor-origin root proof: "
                            + input.displayName() + " at " + root.callable().linkage());
                }
            }
        }
        // Generated destruction is deliberately outside this invocation surface.
        // Source calls may not independently invalidate its Java-owned roots.
        for (var type : referenceTypes) {
            var nonReclamation = BridgeNonReclamationAnalyzer.analyze(program, analysisRoots, type, facts);
            if (nonReclamation.status() != BridgeProof.Status.PROVED) {
                return failed(nonReclamation.status(), "source can invalidate root storage: " + nonReclamation.reason());
            }
        }
        var attribution = permanent.isPresent()
                ? BridgeRetentionAnalyzer.withPermanentValues(program, analysisRoots, facts, permanent.orElseThrow())
                : enumLifetime.isPresent()
                ? BridgeRetentionAnalyzer.withEnumValues(program, analysisRoots, facts, enumLifetime.orElseThrow())
                : BridgeRetentionAnalyzer.analyze(program, analysisRoots, facts);
        for (var entry : attribution.entrySet()) {
            var proof = entry.getValue();
            if (proof.status() != BridgeProof.Status.PROVED) return failed(proof.status(), proof.reason());
            if (entry.getKey().kind() == IrCallableKind.CLASS_INITIALIZER && !proof.contract().orElseThrow().slots().isEmpty()) {
                return BridgeProof.rejected("conversion initializer cannot have entry root slots");
            }
        }
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
                var holder = BridgeGenericDomain.storage(callable.parameters().get(slot.holderInput()));
                if (!rootTypes.contains(holder) || borrowedTypes.contains(holder) || !holder.referenceName().equals(slot.field().ownerClass())) {
                    return BridgeProof.rejected("retaining field is not on an exact constructed root: " + slot);
                }
                fields.get(holder).add(slot.field());
                for (int value : slot.valueInputs()) {
                    var retained = BridgeGenericDomain.storage(callable.parameters().get(value));
                    var rootOwners = owners.get(retained);
                    if (rootOwners == null || rootOwners.isEmpty()) {
                        return BridgeProof.rejected("retained value has no proved independent root owner: " + slot);
                    }
                    graph.get(holder).addAll(rootOwners);
                }
            }
        }
        if (cyclic(graph)) return BridgeProof.rejected("possible repeated-call retention cycle: " + graph);
        Map<IrType, List<IrField>> slots = new LinkedHashMap<>();
        fields.forEach((type, values) -> slots.put(type, values.stream()
                .sorted(Comparator.comparing(IrField::name)).toList()));
        for (var result : results.values()) {
            if (result.kind() == BridgeResultOriginContract.Kind.FRESH_ROOT && !slots.get(BridgeGenericDomain.storage(result.callable().result())).isEmpty()) {
                return BridgeProof.rejected("fresh method results require bounded initial slot reporting: " + result.callable().linkage());
            }
        }
        return BridgeProof.proved(new BridgeRootRetentionContract(program, roots, rootTypes, entries, slots, graph, results, borrowedTypes,
                owners, enumLifetime, permanent),
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
