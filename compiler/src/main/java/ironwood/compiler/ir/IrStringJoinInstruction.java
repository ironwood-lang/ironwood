// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringJoinInstruction(IrValueReference result, IrOperand delimiter, IrOperand elements,
        SourceSpan sourceSpan) implements IrInstruction {
    public IrStringJoinInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !delimiter.type().equals(IrType.reference("ironwood.lang.String"))
                || !elements.type().equals(IrType.array(IrType.reference("ironwood.lang.String")))) {
            throw new IllegalArgumentException("Invalid String join operands");
        }
    }
}
