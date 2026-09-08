// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrPrintStreamCheckErrorInstruction(IrValueReference result, IrOperand stream,
                                                 SourceSpan sourceSpan) implements IrInstruction {
    public IrPrintStreamCheckErrorInstruction {
        if (!result.type().equals(IrType.I1)
                || !stream.type().equals(IrType.reference("ironwood.io.PrintStream"))) {
            throw new IllegalArgumentException("PrintStream checkError requires boolean and stream");
        }
    }
}
