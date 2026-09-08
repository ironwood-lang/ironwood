// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringReplaceTextInstruction(IrValueReference result, IrOperand source, IrOperand target, IrOperand replacement,
        SourceSpan sourceSpan) implements IrInstruction {
    public IrStringReplaceTextInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !source.type().equals(IrType.reference("ironwood.lang.String"))
                || !target.type().equals(IrType.reference("ironwood.lang.String"))
                || !replacement.type().equals(IrType.reference("ironwood.lang.String"))) {
            throw new IllegalArgumentException("Invalid String text replacement operands");
        }
    }
}
