// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrArrayBoundsCheckInstruction(IrValueReference result,
                                            IrOperand array, IrOperand index,
                                            SourceSpan sourceSpan) implements IrInstruction {
    public IrArrayBoundsCheckInstruction {
        if (!result.type().equals(IrType.I1)
                || !array.type().isArray() || !index.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("array bounds check requires an array and int index");
        }
    }
}
