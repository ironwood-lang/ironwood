// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrSystemArrayCopyInstruction(IrOperand source, IrOperand sourcePosition,
                                           IrOperand destination, IrOperand destinationPosition,
                                           IrOperand length,
                                           SourceSpan sourceSpan) implements IrInstruction {
    public IrSystemArrayCopyInstruction {
        if (!source.type().isReference() || !destination.type().isReference()
                || !sourcePosition.type().equals(IrType.I32)
                || !destinationPosition.type().equals(IrType.I32)
                || !length.type().equals(IrType.I32)) {
            throw new IllegalArgumentException(
                    "System.arraycopy requires two references and three int operands");
        }
    }
}
