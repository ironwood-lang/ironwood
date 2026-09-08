// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringRepeatInstruction(IrValueReference result, IrOperand source, IrOperand count,
        SourceSpan sourceSpan) implements IrInstruction {
    public IrStringRepeatInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !source.type().equals(IrType.reference("ironwood.lang.String"))
                || !count.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("Invalid String repeat operands");
        }
    }
}
