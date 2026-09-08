// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Marks the point where an exception has entered a source catch body. */
public record IrExceptionCaughtInstruction(IrOperand exception,
                                           SourceSpan sourceSpan) implements IrInstruction {
    public IrExceptionCaughtInstruction {
        if (!exception.type().isReference() && !exception.type().equals(IrType.EXCEPTION)) {
            throw new IllegalArgumentException("caught exception must be a reference");
        }
    }
}
