// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Allocates a description from the concrete type name and a nullable localized message. */
public record IrThrowableDescriptionInstruction(IrValueReference result, IrOperand throwable,
                                                IrOperand message, SourceSpan sourceSpan)
        implements IrInstruction {
    public IrThrowableDescriptionInstruction {
        IrType string = IrType.reference("ironwood.lang.String");
        if (!result.type().equals(string) || !message.type().equals(string)
                || !throwable.type().isNominalReference()) {
            throw new IllegalArgumentException("Throwable description requires an object and String message");
        }
    }
}
