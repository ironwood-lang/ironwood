// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeCleanupAnalyzer;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

/** Final proof that the emitted root protocol still covers every lifetime effect. */
public final class BridgeFinalRootRetention {
    private final BridgeEntryModule module;
    private final BridgeFinalNonReclamation lifetime;
    private final Map<IrType, BridgeCleanupContract> destruction;
    private final Map<BridgeCallableId, BridgeCleanupContract> rollback;

    private BridgeFinalRootRetention(BridgeEntryModule module, BridgeFinalNonReclamation lifetime,
            Map<IrType, BridgeCleanupContract> destruction, Map<BridgeCallableId, BridgeCleanupContract> rollback) {
        this.module = module;
        this.lifetime = lifetime;
        this.destruction = Map.copyOf(destruction);
        this.rollback = Map.copyOf(rollback);
    }

    public IrProgram program() { return lifetime.program(); }
    public BridgeFinalNonReclamation lifetime() { return lifetime; }
    public BridgeRootRetentionContract protocol() { return module.rootRetention().orElseThrow(); }
    public Map<IrType, BridgeCleanupContract> destruction() { return destruction; }
    public Map<BridgeCallableId, BridgeCleanupContract> rollback() { return rollback; }
    public boolean matches(BridgeEntryModule entries, IrProgram candidate) { return module == entries && lifetime.matches(entries, candidate); }

    public static BridgeProof<BridgeFinalRootRetention> prove(CompilationArtifact artifact, BridgeEntryModule module) {
        if (module.rootRetention().isEmpty()) return BridgeProof.rejected("final root validation requires an admitted root protocol");
        var finalLifetime = BridgeFinalNonReclamation.prove(artifact, module);
        if (finalLifetime.status() != BridgeProof.Status.PROVED) return failure(finalLifetime.status(), finalLifetime.reason());
        var lifetime = finalLifetime.contract().orElseThrow();
        var program = lifetime.program();
        var facts = lifetime.constructionFacts();
        var original = module.rootRetention().orElseThrow();
        var sourceRoots = original.analysisRoots().revalidate(program);
        if (!sourceRoots.resolved()) return BridgeProof.unknown("final root source calls are unresolved");
        var getterRoots = lifetime.exceptions().projection().accessors().revalidate(program);
        if (!getterRoots.resolved()) return BridgeProof.unknown("final exception getters are unresolved");
        var queries = new LinkedHashSet<>(sourceRoots.roots().stream().map(BridgeRootSet.Root::callable).toList());
        getterRoots.roots().forEach(root -> queries.add(root.callable()));
        var sourceAndGetters = BridgeRootSet.resolve(program, java.util.List.copyOf(queries));
        Map<BridgeCallableId, BridgeProof<BridgeRetentionContract>> attribution;
        try { attribution = BridgeRetentionAnalyzer.finalRootEntries(module, lifetime, sourceAndGetters); }
        catch (IllegalArgumentException mismatch) { return BridgeProof.unknown(mismatch.getMessage()); }
        for (var query : queries) {
            var proof = attribution.get(query);
            if (proof == null) return BridgeProof.unknown("final slot attribution missing: " + query.linkage());
            if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
            var expected = original.entries().get(query);
            if (expected != null && !samePayload(expected, proof.contract().orElseThrow())) {
                return BridgeProof.rejected("final slot effects disagree with generated payload: " + query.linkage());
            }
            if (expected == null && !proof.contract().orElseThrow().slots().isEmpty()) {
                return BridgeProof.rejected("getter or initializer has unreported retention effects: " + query.linkage());
            }
        }
        for (var entry : original.resultOrigins().entrySet()) {
            var proof = facts.resultOrigins().get(entry.getKey());
            if (proof == null || proof.status() != BridgeProof.Status.PROVED || !proof.contract().orElseThrow().equals(entry.getValue())) {
                return BridgeProof.unknown("final reference result lost its exact ownership provenance: " + entry.getKey().linkage());
            }
        }
        var destructionSymbols = module.destructions().stream().map(entry -> entry.function().linkageName())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (var entry : module.destructions()) {
            if (program.functions().stream().noneMatch(entry.function()::equals)) {
                return BridgeProof.unknown("generated destruction changed without an exact capability proof");
            }
        }
        var invocation = BridgeRootSet.resolve(program, program.functions().stream().filter(function ->
                program.exportRoots().contains(function.linkageName()) && !destructionSymbols.contains(function.linkageName()))
                .map(BridgeCallableId::of).toList());
        var liveTypes = new LinkedHashSet<>(original.constructedRootTypes());
        liveTypes.addAll(original.borrowedResultTypes());
        for (var type : liveTypes) {
            var proof = BridgeNonReclamationAnalyzer.analyze(program, invocation, type, facts);
            if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), "normal final call can invalidate root storage: " + proof.reason());
        }
        var analyzed = new CompilationArtifact(Optional.of(program), Optional.empty(), artifact.diagnostics(), Optional.of(facts));
        Map<IrType, BridgeCleanupContract> destruction = new LinkedHashMap<>();
        for (var type : original.constructedRootTypes()) {
            var proof = BridgeCleanupAnalyzer.analyze(analyzed, sourceRoots, type, Optional.empty());
            if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
            destruction.put(type, proof.contract().orElseThrow());
        }
        Map<BridgeCallableId, BridgeCleanupContract> rollback = new LinkedHashMap<>();
        for (var root : original.roots().roots()) {
            if (root.callable().kind() != IrCallableKind.CONSTRUCTOR) continue;
            var proof = BridgeCleanupAnalyzer.analyze(analyzed, sourceRoots, IrType.reference(root.callable().owner()), Optional.of(root.callable()));
            if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
            rollback.put(root.callable(), proof.contract().orElseThrow());
        }
        return BridgeProof.proved(new BridgeFinalRootRetention(module, lifetime, destruction, rollback),
                "final slot payload, invocation lifetime and explicit nonthrowing cleanup agree with the admitted root protocol");
    }

    private static BridgeProof<BridgeFinalRootRetention> failure(BridgeProof.Status status, String reason) {
        return status == BridgeProof.Status.REJECTED ? BridgeProof.rejected(reason) : BridgeProof.unknown(reason);
    }

    private static boolean samePayload(BridgeRetentionContract expected, BridgeRetentionContract actual) {
        if (expected.slots().size() != actual.slots().size()) return false;
        for (int index = 0; index < expected.slots().size(); index++) {
            var before = expected.slots().get(index);
            var after = actual.slots().get(index);
            if (before.holderInput() != after.holderInput() || !before.field().equals(after.field())
                    || !before.valueInputs().equals(after.valueInputs()) || before.mayClear() != after.mayClear()) return false;
        }
        // Diagnostic sites retain actual cloned callable names. They are not
        // payload fields; every actual store was already proved above.
        return true;
    }
}
