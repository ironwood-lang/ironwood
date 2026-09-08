// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.Map;

/** A compiler-owned object whose storage lives in the native image for the process lifetime. */
public record IrImmortalObject(String symbol, IrType type, IrType storageType, Map<String, IrConstant> fieldValues,
                               SourceSpan sourceSpan) implements IrOperand {
    public IrImmortalObject {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("immortal object symbol must not be blank");
        }
        if (!type.isNominalReference() || !storageType.isNominalReference()) {
            throw new IllegalArgumentException("immortal object must have a nominal class type");
        }
        fieldValues = fieldValues == null ? Map.of() : Map.copyOf(fieldValues);
        if (fieldValues.keySet().stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("immortal object field names must not be blank");
        }
    }

    public IrImmortalObject(String symbol, IrType type, Map<String, IrConstant> fields, SourceSpan span) {
        this(symbol, type, type, fields, span);
    }

    public IrImmortalObject(String symbol, IrType type, SourceSpan sourceSpan) {
        this(symbol, type, type, Map.of(), sourceSpan);
    }
}
