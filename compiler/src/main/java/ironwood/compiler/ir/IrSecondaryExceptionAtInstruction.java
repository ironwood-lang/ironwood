// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Reads one later exception from a primary exception without allocating an array. */
public record IrSecondaryExceptionAtInstruction(IrValueReference result, IrOperand primary,
                                                IrOperand index,
                                                SourceSpan sourceSpan) implements IrInstruction {
    public IrSecondaryExceptionAtInstruction {
        if (!result.type().isReference()
                || !(primary.type().equals(IrType.EXCEPTION) || primary.type().isReference())
                || !index.type().equals(IrType.I32)) {
            throw new IllegalArgumentException(
                    "secondaryException requires an exception reference and int index");
        }
    }
}
