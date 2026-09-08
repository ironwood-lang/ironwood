// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrNullCheckInstruction(IrValueReference result, IrOperand receiver,
                                     SourceSpan sourceSpan) implements IrInstruction {
    public IrNullCheckInstruction {
        if (!result.type().equals(IrType.I1)) {
            throw new IllegalArgumentException("null check result must have type boolean");
        }
        if (!receiver.type().isReference() && !receiver.type().equals(IrType.NULL)) {
            throw new IllegalArgumentException("null check requires a reference operand");
        }
    }
}
