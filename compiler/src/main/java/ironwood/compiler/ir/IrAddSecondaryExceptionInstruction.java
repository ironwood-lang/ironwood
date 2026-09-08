// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Records a later exception without replacing the exception already being propagated. */
public record IrAddSecondaryExceptionInstruction(IrOperand primary, IrOperand secondary,
                                                 SourceSpan sourceSpan) implements IrInstruction {
    public IrAddSecondaryExceptionInstruction {
        if (!isExceptionReference(primary.type()) || !isExceptionReference(secondary.type())) {
            throw new IllegalArgumentException(
                    "secondary-exception association requires exception references");
        }
    }

    private static boolean isExceptionReference(IrType type) {
        return type.equals(IrType.EXCEPTION) || type.isReference();
    }
}
