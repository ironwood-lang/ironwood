// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringReplaceCharInstruction(IrValueReference result, IrOperand source, IrOperand oldChar, IrOperand newChar,
        SourceSpan sourceSpan) implements IrInstruction {
    public IrStringReplaceCharInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !source.type().equals(IrType.reference("ironwood.lang.String"))
                || !oldChar.type().equals(IrType.U16)
                || !newChar.type().equals(IrType.U16)) {
            throw new IllegalArgumentException("Invalid String character replacement operands");
        }
    }
}
