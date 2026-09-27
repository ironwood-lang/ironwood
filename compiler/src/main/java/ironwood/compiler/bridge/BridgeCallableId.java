// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrType;

import java.util.List;
import java.util.Objects;

/** Resolved identity, independent of source container paths and analysis instances. */
public record BridgeCallableId(String owner, String name, String linkage,
                               IrCallableKind kind, List<IrType> parameters, IrType result) {
    public BridgeCallableId {
        if (owner == null || owner.isBlank() || name == null || name.isBlank()
                || linkage == null || linkage.isBlank()) {
            throw new IllegalArgumentException("bridge callable requires resolved names");
        }
        Objects.requireNonNull(kind);
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(result);
    }

    public static BridgeCallableId of(IrFunction function) {
        return new BridgeCallableId(function.ownerClass(), function.sourceName(),
                function.linkageName(), function.kind(), function.parameters().stream()
                .map(parameter -> parameter.value().type()).toList(), function.returnType());
    }
}
