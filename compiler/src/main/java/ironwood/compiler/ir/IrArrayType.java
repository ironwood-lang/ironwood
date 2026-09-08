// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import java.util.List;

public record IrArrayType(IrType type, int typeId, List<Integer> typeMembership,
                          List<IrDispatchEntry> dispatchEntries,
                          boolean toStringReturnsOwnedFresh) {
    public IrArrayType {
        if (!type.isArray()) {
            throw new IllegalArgumentException("array metadata requires an array IR type");
        }
        typeMembership = List.copyOf(typeMembership);
        dispatchEntries = List.copyOf(dispatchEntries);
    }

    public String name() {
        return type.displayName();
    }
}
