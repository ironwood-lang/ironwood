// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Formats an integer directly into one fresh String, without heap scratch. */
public record IrStringFromIntegerInstruction(IrValueReference result, IrOperand value,
                                             IrOperand radix, SourceSpan sourceSpan)
        implements IrInstruction {
    public IrStringFromIntegerInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !value.type().equals(IrType.I64) || !radix.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("integer String formatting requires long, int");
        }
    }
}
