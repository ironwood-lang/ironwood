// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.semantic.BridgePermanentAnalyzer;
import ironwood.compiler.semantic.BridgeRootRetentionAnalyzer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Complete internal object admission. A final native proof does not supply the pending host adapters. */
public final class BridgeObjectAdmission {
    private static final IrType STRING = IrType.reference("ironwood.lang.String");
    private final CompilationArtifact artifact;
    private final BridgeExportSurface surface;
    private final BridgeEntryModule entries;
    private final BridgeFinalNonReclamation lifetime;
    private final Optional<BridgeFinalRootRetention> roots;
    private final Map<IrType, List<String>> reasons;

    private BridgeObjectAdmission(CompilationArtifact artifact, BridgeExportSurface surface, BridgeEntryModule entries,
            BridgeFinalNonReclamation lifetime, Optional<BridgeFinalRootRetention> roots, Map<IrType, List<String>> reasons) {
        this.artifact = artifact;
        this.surface = surface;
        this.entries = entries;
        this.lifetime = lifetime;
        this.roots = roots;
        this.reasons = Map.copyOf(reasons);
    }

    public BridgeExportSurface surface() { return surface; }
    public BridgeEntryModule entries() { return entries; }
    public BridgeFinalNonReclamation lifetime() { return lifetime; }
    public Optional<BridgeFinalRootRetention> roots() { return roots; }
    public Map<IrType, List<String>> permanentReasons() { return reasons; }
    public IrProgram program() { return lifetime.program(); }
    public boolean matches(CompilationArtifact candidate, BridgeExportSurface api) {
        return artifact.equals(candidate) && surface.equals(api) && lifetime.matches(entries, program());
    }

    public static BridgeProof<BridgeObjectAdmission> prove(CompilationArtifact artifact, List<String> exports) {
        var selected = BridgeExportSurface.objectValues(artifact, exports);
        if (selected.surface().isEmpty()) return BridgeProof.rejected(selected.diagnostics().toString());
        var surface = selected.surface().orElseThrow();
        if (!surface.roots().resolved()) return BridgeProof.rejected("object admission requires native object entry roots");
        try {
            var mappings = BridgeEnumConversions.forSurface(artifact, surface);
            Set<IrType> enums = new LinkedHashSet<>(mappings.enumTypes());
            mappings.parameters().forEach((id, parameters) -> parameters.stream().filter(parameter -> !parameter.nullable())
                    .forEach(parameter -> enums.add(id.parameters().get(parameter.input()))));
            Set<IrType> objects = new LinkedHashSet<>();
            for (var root : surface.roots().roots()) {
                objects.addAll(root.callable().parameters());
                objects.add(root.callable().result());
            }
            objects.removeIf(type -> !type.isReference() || type.equals(STRING) || enums.contains(type)
                    || ironwood.compiler.semantic.BridgeArrayInputs.primitiveArray(type));
            if (objects.isEmpty() && enums.isEmpty() && surface.types().stream().noneMatch(type -> type.throwable())) {
                return BridgeProof.rejected("object admission requires concrete objects, enums or custom snapshots");
            }
            var reasons = candidates(artifact, surface, objects);
            var analysisRoots = BridgeRootSet.resolve(artifact.program().orElseThrow(), java.util.stream.Stream.concat(
                    surface.roots().roots().stream().map(BridgeRootSet.Root::callable), mappings.initializers().stream()).toList());
            Set<IrType> candidates = new LinkedHashSet<>(enums);
            candidates.addAll(reasons.keySet());
            for (var candidate : candidates) {
                var proof = BridgeNonReclamationAnalyzer.analyze(artifact.program().orElseThrow(), analysisRoots, candidate,
                        artifact.bridgeConstructionFacts().orElseThrow());
                if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
            }
            BridgeEntryModule module;
            if (reasons.keySet().containsAll(objects)) {
                var admitted = BridgePermanentAnalyzer.analyze(artifact, surface.roots(), mappings);
                if (admitted.status() != BridgeProof.Status.PROVED) return failure(admitted.status(), admitted.reason());
                module = BridgeEntryModule.permanentObjects(artifact, surface.roots(), mappings);
                var proof = BridgeFinalNonReclamation.prove(artifact, module, surface);
                if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
                return BridgeProof.proved(new BridgeObjectAdmission(artifact, surface, module, proof.contract().orElseThrow(),
                        Optional.empty(), reasons), "complete permanent object admission with final storage proof");
            }
            var permanent = reasons.isEmpty() ? Optional.<BridgePermanentValues>empty()
                    : Optional.of(BridgePermanentValues.prove(artifact, surface.roots(), reasons.keySet(), mappings));
            var admitted = permanent.map(values -> BridgeRootRetentionAnalyzer.analyze(artifact, surface.roots(), values))
                    .orElseGet(() -> BridgeRootRetentionAnalyzer.analyze(artifact, surface.roots(), mappings));
            if (admitted.status() != BridgeProof.Status.PROVED) return failure(admitted.status(), admitted.reason());
            module = permanent.map(values -> BridgeEntryModule.rootObjects(artifact, surface.roots(), values))
                    .orElseGet(() -> BridgeEntryModule.rootObjects(artifact, surface.roots(), mappings));
            var proof = BridgeFinalRootRetention.prove(artifact, module, surface);
            if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
            var roots = proof.contract().orElseThrow();
            return BridgeProof.proved(new BridgeObjectAdmission(artifact, surface, module, roots.lifetime(), Optional.of(roots), reasons),
                    "complete root and permanent object admission with final slot and destruction proofs");
        } catch (IllegalArgumentException refused) {
            return BridgeProof.rejected(refused.getMessage());
        }
    }

    private static Map<IrType, List<String>> candidates(CompilationArtifact artifact, BridgeExportSurface surface, Set<IrType> objects) {
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        Map<IrType, Set<String>> reasons = new LinkedHashMap<>();
        var generics = BridgeGenericDomain.discover(artifact, surface.types());
        var publications = BridgeRetentionAnalyzer.publishedReceivers(artifact.program().orElseThrow(), surface.roots(), facts);
        for (var root : surface.roots().roots()) {
            var id = root.callable();
            var origin = facts.resultOrigins().get(id);
            if (objects.contains(id.result()) && origin != null && origin.status() == BridgeProof.Status.UNKNOWN) {
                reasons.computeIfAbsent(id.result(), ignored -> new LinkedHashSet<>()).add("unknown result origin: " + id.linkage());
            }
            if (publications.containsKey(id) && objects.contains(id.parameters().getFirst())) {
                reasons.computeIfAbsent(id.parameters().getFirst(), ignored -> new LinkedHashSet<>())
                        .add("receiver publication: " + id.linkage() + " at " + publications.get(id));
            }
        }
        boolean changed;
        do {
            changed = false;
            for (var root : surface.roots().roots()) {
                var id = root.callable();
                var origin = facts.resultOrigins().get(id);
                if (!objects.contains(id.result()) || reasons.containsKey(id.result()) || origin == null
                        || origin.status() != BridgeProof.Status.PROVED) continue;
                var result = origin.contract().orElseThrow();
                if (result.kind() == BridgeResultOriginContract.Kind.DEPENDENT_VIEW
                        && reasons.containsKey(id.parameters().get(result.inputs().iterator().next()))) {
                    reasons.put(id.result(), new LinkedHashSet<>(Set.of("dependent view of permanent candidate: " + id.linkage())));
                    changed = true;
                }
            }
            // A variable result may expose any of its native production alternatives.
            // Every alternative needs the same independent non-reclamation proof.
            for (var entry : generics.variables().entrySet()) {
                if (reasons.keySet().stream().anyMatch(type -> type.isTypeParameter() && type.referenceName().equals(entry.getKey()))) {
                    for (var alternative : entry.getValue()) {
                        if (!reasons.containsKey(alternative)) changed = true;
                        reasons.computeIfAbsent(alternative, ignored -> new LinkedHashSet<>())
                                .add("finite generic result alternative: " + entry.getKey());
                    }
                }
            }
            // Native reference applications share storage and one Java facade class.
            // Publication through any application therefore requires the family-wide
            // permanent proof, including its declaration receiver view.
            for (var type : objects) {
                if (!type.isNominalReference() || !generics.applications().containsKey(type.referenceName())) continue;
                if (reasons.keySet().stream().anyMatch(other -> other.isNominalReference()
                        && other.referenceName().equals(type.referenceName()))) {
                    if (!reasons.containsKey(type)) changed = true;
                    reasons.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add("shared reference-generic storage family: " + type.referenceName());
                }
            }
        } while (changed);
        Map<IrType, List<String>> result = new LinkedHashMap<>();
        reasons.forEach((type, values) -> result.put(type, values.stream().sorted().toList()));
        return Map.copyOf(result);
    }

    private static BridgeProof<BridgeObjectAdmission> failure(BridgeProof.Status status, String reason) {
        return status == BridgeProof.Status.REJECTED ? BridgeProof.rejected(reason) : BridgeProof.unknown(reason);
    }
}
