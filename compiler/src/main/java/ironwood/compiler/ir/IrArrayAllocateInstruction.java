// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrArrayAllocateInstruction(IrValueReference result, IrType elementType,
                                         IrOperand length, SourceSpan sourceSpan) implements IrInstruction {
    public IrArrayAllocateInstruction {
        if (!result.type().equals(IrType.array(elementType))) {
            throw new IllegalArgumentException("array allocation result type must match its element type");
        }
        if (!length.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("array allocation length must have type int");
        }
    }
}
