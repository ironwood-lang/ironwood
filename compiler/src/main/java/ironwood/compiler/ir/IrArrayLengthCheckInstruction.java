// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Tests whether a source-evaluated array length is non-negative. */
public record IrArrayLengthCheckInstruction(IrValueReference result, IrOperand length,
                                            SourceSpan sourceSpan) implements IrInstruction {
    public IrArrayLengthCheckInstruction {
        if (!result.type().equals(IrType.I1) || !length.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("array length check requires a boolean result and int length");
        }
    }
}
