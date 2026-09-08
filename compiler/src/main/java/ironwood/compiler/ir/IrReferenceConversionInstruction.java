// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrReferenceConversionInstruction(IrValueReference result, IrOperand value,
                                               SourceSpan sourceSpan) implements IrInstruction {
    public IrReferenceConversionInstruction {
        if (!result.type().isReference()
                || !value.type().isReference() && !value.type().equals(IrType.EXCEPTION)) {
            throw new IllegalArgumentException("reference conversion requires reference operands");
        }
    }
}
