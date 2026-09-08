// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** One compiler-emitted immortal enum singleton in the final native image. */
public record IrEnumConstant(String symbol, IrType type, IrType storageType,
                             String constantName, int ordinal,
                             IrStringConstant nameValue,
                             SourceSpan sourceSpan) implements IrOperand {
    public IrEnumConstant {
        if (symbol == null || symbol.isBlank() || constantName == null || constantName.isBlank()) {
            throw new IllegalArgumentException("enum constant identity must not be blank");
        }
        if (!type.isNominalReference() || !storageType.isNominalReference() || ordinal < 0) {
            throw new IllegalArgumentException(
                    "enum constant requires declared and storage nominal types plus an ordinal");
        }
    }
}
