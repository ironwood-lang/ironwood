// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;
import java.util.List;

/** Resolved bounds plus declaration erasure, which owner substitution must not replace. */
public record BridgeTypeParameter(String id, String name, List<IrType> upperBounds,
                                  IrType erasure, boolean permitsPrimitive) {
    public BridgeTypeParameter {
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("bridge type parameter requires scoped identity and name");
        }
        upperBounds = List.copyOf(upperBounds);
        if (upperBounds.isEmpty() || upperBounds.stream().anyMatch(bound -> !bound.isReference())
                || erasure == null || !erasure.isReference() || !erasure.equals(erasure.erasure())
                || permitsPrimitive && !upperBounds.equals(List.of(IrType.reference("ironwood.lang.Object")))) {
            throw new IllegalArgumentException("bridge type parameter requires complete resolved bounds");
        }
    }
}
