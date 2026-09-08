// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** An explicit Java primitive numeric conversion retained until LLVM lowering. */
public record IrNumericConversionInstruction(IrValueReference result, IrOperand value,
                                             SourceSpan sourceSpan) implements IrInstruction {
    public IrNumericConversionInstruction {
        if (!result.type().isNumeric() || !value.type().isNumeric()
                || result.type().equals(value.type())) {
            throw new IllegalArgumentException("numeric conversion requires distinct numeric source and target types");
        }
    }
}
