// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringCaseInstruction(IrValueReference result, IrOperand source, IrOperand upper,
        SourceSpan sourceSpan) implements IrInstruction {
    public IrStringCaseInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !source.type().equals(IrType.reference("ironwood.lang.String"))
                || !upper.type().equals(IrType.I1)) {
            throw new IllegalArgumentException("Invalid String case operands");
        }
    }
}
