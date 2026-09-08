// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrPrintStreamFlushInstruction(IrOperand stream,
                                            SourceSpan sourceSpan) implements IrInstruction {
    public IrPrintStreamFlushInstruction {
        if (!stream.type().equals(IrType.reference("ironwood.io.PrintStream"))) {
            throw new IllegalArgumentException("PrintStream flush requires a PrintStream receiver");
        }
    }
}
