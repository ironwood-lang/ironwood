// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrField;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Final semantic facts, not permission to exclude an arbitrary cleanup site. */
public record BridgeConstructionContract(BridgeCallableId constructor,
                                         List<IrField> ownedStorageFields,
                                         Set<IrField> ownedElementFields) {
    public BridgeConstructionContract {
        Objects.requireNonNull(constructor);
        ownedStorageFields = List.copyOf(ownedStorageFields);
        ownedElementFields = Set.copyOf(ownedElementFields);
        if (!ownedStorageFields.containsAll(ownedElementFields)) {
            throw new IllegalArgumentException("owned elements require an owned array field");
        }
    }
}
