// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringFromRangeInstruction(IrValueReference result, IrOperand source,
                                           IrOperand beginIndex, IrOperand length,
                                           SourceSpan sourceSpan) implements IrInstruction {
    public IrStringFromRangeInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !source.type().equals(IrType.reference("ironwood.lang.String"))
                || !beginIndex.type().equals(IrType.I32)
                || !length.type().equals(IrType.I32)) {
            throw new IllegalArgumentException(
                    "String range snapshot requires String, int, and int and returns String");
        }
    }
}
