// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrNull(IrType type, SourceSpan sourceSpan) implements IrOperand {
    public IrNull {
        if (!type.isReference() && !type.equals(IrType.NULL)) {
            throw new IllegalArgumentException("null operand requires a reference or null type");
        }
    }
}
