// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringCharAtInstruction(IrValueReference result, IrOperand string,
                                        IrOperand index,
                                        SourceSpan sourceSpan) implements IrInstruction {
    public IrStringCharAtInstruction {
        if (!result.type().equals(IrType.U16)
                || !string.type().equals(IrType.reference("ironwood.lang.String"))
                || !index.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("String.charAt requires String and int and returns char");
        }
    }
}
