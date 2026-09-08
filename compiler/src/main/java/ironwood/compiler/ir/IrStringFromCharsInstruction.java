// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringFromCharsInstruction(IrValueReference result, IrOperand characters,
                                           IrOperand length,
                                           SourceSpan sourceSpan) implements IrInstruction {
    public IrStringFromCharsInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !characters.type().equals(IrType.array(IrType.U16))
                || !length.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("String snapshot requires char[] and int and returns String");
        }
    }
}
