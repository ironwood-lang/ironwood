// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrArrayLengthInstruction(IrValueReference result, IrOperand array,
                                       SourceSpan sourceSpan) implements IrInstruction {
    public IrArrayLengthInstruction {
        if (!result.type().equals(IrType.I32) || !array.type().isArray()) {
            throw new IllegalArgumentException("array length requires an array and produces int");
        }
    }
}
