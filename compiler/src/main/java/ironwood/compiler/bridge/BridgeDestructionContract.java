// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;

import java.util.List;
import java.util.Optional;

/** Complete nonthrowing, allocation-free, callback-free descriptor cleanup for an admitted root. */
public record BridgeDestructionContract(BridgeRootRetentionContract ownership, IrType type,
                                         Optional<BridgeCallableId> unpublishedConstructor,
                                         List<BridgeCallableId> checkedCleanup) {
    public BridgeDestructionContract {
        checkedCleanup = List.copyOf(checkedCleanup);
        if (!ownership.constructedRootTypes().contains(type)) {
            throw new IllegalArgumentException("destruction requires a constructed root type");
        }
        unpublishedConstructor.ifPresent(constructor -> {
            if (!constructor.owner().equals(type.referenceName())
                    || constructor.kind() != ironwood.compiler.ir.IrCallableKind.CONSTRUCTOR) {
                throw new IllegalArgumentException("rollback proof requires its exact constructor");
            }
        });
    }
}
