// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrArrayStoreInstruction(IrOperand array, IrOperand index, IrOperand value,
                                      SourceSpan sourceSpan) implements IrInstruction {
    public IrArrayStoreInstruction {
        if (!array.type().isArray() || !index.type().equals(IrType.I32)
                || !value.type().equals(array.type().elementType())) {
            throw new IllegalArgumentException("array store types must match");
        }
    }
}
