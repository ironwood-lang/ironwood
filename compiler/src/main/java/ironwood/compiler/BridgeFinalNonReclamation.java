// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.semantic.BridgeConstructionFacts;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Final storage-lifetime proofs over all emitted entries, cleanup and exception getters. */
public final class BridgeFinalNonReclamation {
    private final BridgeEntryModule module;
    private final BridgeExceptionClosure.Snapshot exceptions;
    private final IrProgram program;
    private final Map<IrType, BridgeNonReclamationContract> references;
    private final BridgeConstructionFacts constructionFacts;

    private BridgeFinalNonReclamation(BridgeEntryModule module, BridgeExceptionClosure.Snapshot exceptions,
            IrProgram program, Map<IrType, BridgeNonReclamationContract> references, BridgeConstructionFacts constructionFacts) {
        this.module = module;
        this.exceptions = exceptions;
        this.program = program;
        this.references = Map.copyOf(references);
        this.constructionFacts = constructionFacts;
    }

    public IrProgram program() { return program; }
    public BridgeExceptionClosure.Snapshot exceptions() { return exceptions; }
    public Map<IrType, BridgeNonReclamationContract> references() { return references; }
    public BridgeConstructionFacts constructionFacts() { return constructionFacts; }
    public boolean matches(BridgeEntryModule entries, IrProgram candidate) { return module == entries && program.equals(candidate); }

    public static BridgeProof<BridgeFinalNonReclamation> prove(CompilationArtifact artifact, BridgeEntryModule module) {
        return prove(artifact, module, java.util.Optional.empty());
    }

    public static BridgeProof<BridgeFinalNonReclamation> prove(CompilationArtifact artifact, BridgeEntryModule module,
            BridgeExportSurface surface) {
        return prove(artifact, module, java.util.Optional.of(surface));
    }

    private static BridgeProof<BridgeFinalNonReclamation> prove(CompilationArtifact artifact, BridgeEntryModule module,
            java.util.Optional<BridgeExportSurface> surface) {
        if (!artifact.valid() || artifact.program().isEmpty() || artifact.bridgeConstructionFacts().isEmpty()
                || !artifact.bridgeConstructionFacts().orElseThrow().matches(artifact.program().orElseThrow())) {
            return BridgeProof.unknown("final lifetime requires matching bridge semantic facts");
        }
        Set<IrType> candidates = new LinkedHashSet<>();
        module.permanent().ifPresent(contract -> candidates.addAll(contract.references().keySet()));
        module.enumInvocation().ifPresent(contract -> candidates.addAll(contract.lifetime().references().keySet()));
        module.rootRetention().flatMap(BridgeRootRetentionContract::enumLifetime)
                .ifPresent(lifetime -> candidates.addAll(lifetime.contract().references().keySet()));
        if (candidates.isEmpty() && module.rootRetention().isEmpty()) {
            return BridgeProof.rejected("final lifetime requires admitted object or enum references");
        }
        var closure = surface.map(api -> BridgeExceptionClosure.snapshots(artifact, module, api))
                .orElseGet(() -> BridgeExceptionClosure.snapshots(artifact, module));
        if (closure.status() != BridgeProof.Status.PROVED) return failure(closure.status(), closure.reason());
        var exceptions = closure.contract().orElseThrow();
        try {
            var facts = artifact.bridgeConstructionFacts().orElseThrow().withGeneratedEntries(module, exceptions.entries());
            var transformation = NativeLinkTransformation.apply(exceptions.entries().program());
            var program = transformation.program();
            facts = facts.afterNativeLink(transformation);
            var roots = BridgeRootSet.resolve(program, program.functions().stream()
                    .filter(function -> program.exportRoots().contains(function.linkageName())).map(BridgeCallableId::of).toList());
            if (!roots.resolved() || roots.roots().size() != program.exportRoots().size()) {
                return BridgeProof.unknown("final lifetime requires every emitted native root");
            }
            Map<IrType, BridgeNonReclamationContract> references = new LinkedHashMap<>();
            for (var type : candidates.stream().sorted(java.util.Comparator.comparing(IrType::displayName)).toList()) {
                var proof = BridgeNonReclamationAnalyzer.analyze(program, roots, type, facts);
                if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
                references.put(type, proof.contract().orElseThrow());
            }
            return BridgeProof.proved(new BridgeFinalNonReclamation(module, exceptions, program, references, facts),
                    "all admitted permanent/enum references survive the complete final generated and exception closure");
        } catch (IllegalArgumentException mismatch) {
            return BridgeProof.unknown("final lifetime binding failed: " + mismatch.getMessage());
        }
    }

    private static BridgeProof<BridgeFinalNonReclamation> failure(BridgeProof.Status status, String reason) {
        return status == BridgeProof.Status.REJECTED ? BridgeProof.rejected(reason) : BridgeProof.unknown(reason);
    }
}
