// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrArrayLoadInstruction(IrValueReference result, IrOperand array, IrOperand index,
                                     SourceSpan sourceSpan) implements IrInstruction {
    public IrArrayLoadInstruction {
        if (!array.type().isArray() || !index.type().equals(IrType.I32)
                || !result.type().equals(array.type().elementType())) {
            throw new IllegalArgumentException("array load types must match");
        }
    }
}
