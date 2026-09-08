// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringFromCharRangeInstruction(IrValueReference result, IrOperand characters,
                                               IrOperand offset, IrOperand length,
                                               SourceSpan sourceSpan) implements IrInstruction {
    public IrStringFromCharRangeInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !characters.type().equals(IrType.array(IrType.U16))
                || !offset.type().equals(IrType.I32) || !length.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("String char-range copy requires char[], int, int");
        }
    }
}
