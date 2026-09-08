// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringEqualsIgnoreCaseInstruction(IrValueReference result, IrOperand source, IrOperand other,
        SourceSpan sourceSpan) implements IrInstruction {
    public IrStringEqualsIgnoreCaseInstruction {
        if (!result.type().equals(IrType.I1)
                || !source.type().equals(IrType.reference("ironwood.lang.String"))
                || !other.type().equals(IrType.reference("ironwood.lang.String"))) {
            throw new IllegalArgumentException("Invalid String equalsIgnoreCase operands");
        }
    }
}
