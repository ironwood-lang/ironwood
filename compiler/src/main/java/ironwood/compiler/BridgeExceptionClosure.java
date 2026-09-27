// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeEntryModule;
import ironwood.compiler.bridge.BridgeExceptionEntries;
import ironwood.compiler.bridge.BridgeExceptionProjection;
import ironwood.compiler.bridge.BridgeExportSurface;
import ironwood.compiler.bridge.BridgeProof;

import java.util.LinkedHashSet;
import java.util.Set;

/** Exception admission over the same closed-world closure used for native linking. */
public final class BridgeExceptionClosure {
    private BridgeExceptionClosure() {}

    public record Snapshot(BridgeExceptionProjection projection, BridgeExceptionEntries entries) {
        public Snapshot {
            if (!entries.matches(projection)) throw new IllegalArgumentException("exception closure projection mismatch");
        }
    }

    public static BridgeProof<Snapshot> builtins(CompilationArtifact artifact, BridgeEntryModule module) {
        return discover(artifact, module, false);
    }

    /** Internal P3 closure; snapshot transport remains a separate producer capability. */
    public static BridgeProof<Snapshot> snapshots(CompilationArtifact artifact, BridgeEntryModule module) {
        return discover(artifact, module, true);
    }

    private static BridgeProof<Snapshot> discover(CompilationArtifact artifact, BridgeEntryModule module, boolean custom) {
        if (!artifact.valid() || artifact.program().isEmpty()) return BridgeProof.unknown("exception closure requires valid bridge analysis");
        var program = artifact.program().orElseThrow();
        var throwable = program.classes().stream().filter(type -> type.name().equals("ironwood.lang.Throwable")).findFirst();
        if (throwable.isEmpty()) return BridgeProof.unknown("exception closure requires the native Throwable hierarchy");
        Set<String> names = new LinkedHashSet<>(Set.of("ironwood.lang.OutOfMemoryError"));
        for (;;) {
            var projected = custom ? BridgeExceptionProjection.snapshots(artifact, names)
                    : BridgeExceptionProjection.builtins(artifact, names);
            if (projected.status() != BridgeProof.Status.PROVED) {
                return new BridgeProof<>(projected.status(), java.util.Optional.empty(), projected.reason());
            }
            var projection = projected.contract().orElseThrow();
            BridgeExceptionEntries entries;
            try {
                entries = BridgeExceptionEntries.attach(artifact, module, projection);
            } catch (IllegalArgumentException mismatch) {
                return BridgeProof.unknown("exception closure entry binding failed: " + mismatch.getMessage());
            }
            var reachable = ClosedWorldPruner.prune(entries.program());
            boolean added = false;
            for (var type : reachable.classes()) {
                if (!type.typeMembership().contains(throwable.orElseThrow().typeId())) continue;
                if (!custom && !BridgeExportSurface.builtinThrowableNames().contains(type.name())) {
                    return BridgeProof.rejected("reachable custom exception requires P3 snapshot support: " + type.name());
                }
                added |= names.add(type.name());
            }
            if (!added) return BridgeProof.proved(new Snapshot(projection, entries),
                    "exception and protected getter closure reached a closed-world fixed point");
        }
    }
}
