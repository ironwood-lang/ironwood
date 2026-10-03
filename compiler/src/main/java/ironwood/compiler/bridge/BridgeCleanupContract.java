// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;

import java.util.List;
import java.util.Optional;

/** Nonthrowing, allocation-free cleanup effects bound to their analyzed program. */
public record BridgeCleanupContract(IrProgram program, IrType type,
        Optional<BridgeCallableId> unpublishedConstructor, List<BridgeCallableId> checkedCleanup) {
    public BridgeCleanupContract {
        checkedCleanup = List.copyOf(checkedCleanup);
    }
}
