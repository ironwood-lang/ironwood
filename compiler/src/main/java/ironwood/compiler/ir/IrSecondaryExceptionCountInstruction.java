// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Reads the number of later exceptions associated with a primary exception. */
public record IrSecondaryExceptionCountInstruction(IrValueReference result, IrOperand primary,
                                                   SourceSpan sourceSpan) implements IrInstruction {
    public IrSecondaryExceptionCountInstruction {
        if (!result.type().equals(IrType.I32)
                || !(primary.type().equals(IrType.EXCEPTION) || primary.type().isReference())) {
            throw new IllegalArgumentException(
                    "secondaryExceptionCount requires an exception reference and returns int");
        }
    }
}
