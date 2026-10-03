// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Closed-world storage lifetime fact, separate from return-origin or borrowing facts. */
public record BridgeNonReclamationContract(IrType exposedType, Set<IrType> dynamicTypes,
                                           List<BridgeCallableId> exportRoots,
                                           List<BridgeCallableId> checkedClosure,
                                           List<BridgeUnpublishedCleanup> unpublishedCleanups) {
    public BridgeNonReclamationContract {
        Objects.requireNonNull(exposedType);
        dynamicTypes = Set.copyOf(dynamicTypes);
        exportRoots = List.copyOf(exportRoots);
        checkedClosure = List.copyOf(checkedClosure);
        unpublishedCleanups = List.copyOf(unpublishedCleanups);
        if (dynamicTypes.isEmpty() || exportRoots.isEmpty() || checkedClosure.isEmpty()) {
            throw new IllegalArgumentException("non-reclamation requires types, roots and a checked closure");
        }
    }
}
