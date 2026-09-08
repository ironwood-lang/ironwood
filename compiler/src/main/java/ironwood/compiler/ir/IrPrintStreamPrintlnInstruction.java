// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrPrintStreamPrintlnInstruction(IrOperand value,
                                              SourceSpan sourceSpan) implements IrInstruction {
    public IrPrintStreamPrintlnInstruction {
        if (!value.type().equals(IrType.reference("ironwood.lang.String"))) {
            throw new IllegalArgumentException("PrintStream.println requires a String operand");
        }
    }
}
