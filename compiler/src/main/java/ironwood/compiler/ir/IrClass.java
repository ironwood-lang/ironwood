// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record IrClass(String name, IrTypeKind kind, Optional<String> superclass,
                      List<String> interfaces, List<IrField> fields, int typeId,
                      List<Integer> typeMembership, List<IrDispatchEntry> dispatchEntries,
                      SourceSpan sourceSpan) {
    public IrClass {
        superclass = superclass == null ? Optional.empty() : superclass;
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        typeMembership = List.copyOf(typeMembership);
        dispatchEntries = List.copyOf(dispatchEntries);
    }

    public boolean isClass() {
        return kind == IrTypeKind.CLASS;
    }
}
