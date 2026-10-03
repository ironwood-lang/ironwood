// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.source.SourceSpan;

import java.util.Objects;

/** Allocation origin and semantic authority for one failed-construction exclusion. */
public record BridgeUnpublishedCleanup(BridgeCallableId caller, SourceSpan allocationSpan,
                                       Kind kind, BridgeCallableId cleanup,
                                       BridgeConstructionContract construction) {
    public enum Kind { ENTRY_FAILURE, CONSTRUCTOR_UNWIND }

    public BridgeUnpublishedCleanup {
        Objects.requireNonNull(caller);
        Objects.requireNonNull(allocationSpan);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(cleanup);
        Objects.requireNonNull(construction);
    }
}
