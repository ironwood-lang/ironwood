// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

import java.util.Optional;

/** Proves the destruction operation separately from ordinary bridge invocation roots. */
public final class BridgeDestructionAnalyzer {
    private BridgeDestructionAnalyzer() {}

    public static BridgeProof<BridgeDestructionContract> analyze(
            CompilationArtifact artifact, BridgeRootSet roots, IrType type) {
        return analyze(artifact, roots, type, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static BridgeProof<BridgeDestructionContract> analyze(
            CompilationArtifact artifact, BridgeRootSet roots, IrType type, BridgeEnumConversions conversions) {
        return analyze(artifact, roots, type, Optional.empty(), Optional.of(conversions), Optional.empty());
    }

    public static BridgeProof<BridgeDestructionContract> analyze(
            CompilationArtifact artifact, BridgeRootSet roots, IrType type, BridgePermanentValues values) {
        return analyze(artifact, roots, type, Optional.empty(), Optional.empty(), Optional.of(values));
    }

    public static BridgeProof<BridgeDestructionContract> rollback(
            CompilationArtifact artifact, BridgeRootSet roots, BridgeCallableId constructor) {
        return analyze(artifact, roots, IrType.reference(constructor.owner()), Optional.of(constructor), Optional.empty(), Optional.empty());
    }

    public static BridgeProof<BridgeDestructionContract> rollback(
            CompilationArtifact artifact, BridgeRootSet roots, BridgeCallableId constructor, BridgeEnumConversions conversions) {
        return analyze(artifact, roots, IrType.reference(constructor.owner()), Optional.of(constructor), Optional.of(conversions), Optional.empty());
    }

    private static BridgeProof<BridgeDestructionContract> analyze(
            CompilationArtifact artifact, BridgeRootSet roots, IrType type, Optional<BridgeCallableId> constructor,
            Optional<BridgeEnumConversions> conversions, Optional<BridgePermanentValues> permanent) {
        var ownership = permanent.map(value -> BridgeRootRetentionAnalyzer.analyze(artifact, roots, value))
                .orElseGet(() -> conversions.map(mapping -> BridgeRootRetentionAnalyzer.analyze(artifact, roots, mapping))
                .orElseGet(() -> BridgeRootRetentionAnalyzer.analyze(artifact, roots)));
        if (ownership.status() != BridgeProof.Status.PROVED) return failure(ownership.status(), ownership.reason());
        var contract = ownership.contract().orElseThrow();
        if (!contract.constructedRootTypes().contains(type)) return BridgeProof.rejected("type is not an admitted constructed root");
        var cleanup = BridgeCleanupAnalyzer.analyze(artifact, contract.analysisRoots(), type, constructor);
        if (cleanup.status() != BridgeProof.Status.PROVED) return failure(cleanup.status(), cleanup.reason());
        return BridgeProof.proved(new BridgeDestructionContract(contract, type, constructor,
                cleanup.contract().orElseThrow().checkedCleanup()), cleanup.reason());
    }

    private static BridgeProof<BridgeDestructionContract> failure(BridgeProof.Status status, String reason) {
        return status == BridgeProof.Status.REJECTED ? BridgeProof.rejected(reason) : BridgeProof.unknown(reason);
    }
}
