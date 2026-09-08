// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Copies one UTF-16 code unit directly into one fresh String. */
public record IrStringFromCharacterInstruction(IrValueReference result, IrOperand value,
                                               SourceSpan sourceSpan) implements IrInstruction {
    public IrStringFromCharacterInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !value.type().equals(IrType.U16)) {
            throw new IllegalArgumentException("character String formatting requires char");
        }
    }
}
