// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record IrClass(String name, IrTypeKind kind, Optional<String> superclass,
                      List<String> interfaces, List<IrField> fields, int typeId,
                      List<Integer> typeMembership, List<IrDispatchEntry> dispatchEntries,
                      Optional<String> destructorChain,
                      Optional<String> constructorRollback,
                      boolean toStringReturnsOwnedFresh,
                      boolean localizedMessageReturnsOwnedFresh,
                      SourceSpan sourceSpan) {
    public IrClass {
        superclass = superclass == null ? Optional.empty() : superclass;
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        typeMembership = List.copyOf(typeMembership);
        dispatchEntries = List.copyOf(dispatchEntries);
        destructorChain = destructorChain == null ? Optional.empty() : destructorChain;
        constructorRollback = constructorRollback == null
                ? Optional.empty() : constructorRollback;
    }

    public IrClass(String name, IrTypeKind kind, Optional<String> superclass,
                   List<String> interfaces, List<IrField> fields, int typeId,
                   List<Integer> typeMembership, List<IrDispatchEntry> dispatchEntries,
                   SourceSpan sourceSpan) {
        this(name, kind, superclass, interfaces, fields, typeId, typeMembership,
                dispatchEntries, Optional.empty(), Optional.empty(), false, false, sourceSpan);
    }

    public boolean isClass() {
        return kind == IrTypeKind.CLASS;
    }
}
