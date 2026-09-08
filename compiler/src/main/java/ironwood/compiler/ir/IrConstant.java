// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrConstant(IrType type, Number value, SourceSpan sourceSpan) implements IrOperand {
    public IrConstant {
        if (!type.equals(IrType.I1) && !type.isNumeric()) {
            throw new IllegalArgumentException("constants require a value type");
        }
    }
}
