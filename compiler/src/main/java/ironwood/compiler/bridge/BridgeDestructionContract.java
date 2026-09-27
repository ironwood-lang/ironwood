// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;

import java.util.List;

/** Complete nonthrowing, allocation-free, callback-free descriptor cleanup for an admitted root. */
public record BridgeDestructionContract(BridgeRootRetentionContract ownership, IrType type,
                                         List<BridgeCallableId> checkedCleanup) {
    public BridgeDestructionContract {
        checkedCleanup = List.copyOf(checkedCleanup);
        if (!ownership.constructedRootTypes().contains(type)) {
            throw new IllegalArgumentException("destruction requires a constructed root type");
        }
    }
}
