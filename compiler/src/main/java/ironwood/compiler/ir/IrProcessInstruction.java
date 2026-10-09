// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/**
 * A synchronous program launch owned by the native runtime boundary (B4): an
 * absolute command vector, an optional child working directory and the file
 * receiving the merged output. The 64-bit result is the completion status or
 * an encoded launch failure; the operation borrows its operands and may raise
 * an allocation failure while encoding them.
 */
public record IrProcessInstruction(IrValueReference result, IrOperand command, IrOperand directory,
                                   IrOperand output, SourceSpan sourceSpan) implements IrInstruction {
    public static final IrType COMMAND = IrType.array(IrType.reference("ironwood.lang.String"));
    public static final IrType STRING = IrType.reference("ironwood.lang.String");

    public IrProcessInstruction {
        if (!result.type().equals(IrType.I64) || !command.type().equals(COMMAND)
                || !directory.type().equals(STRING) || !output.type().equals(STRING)) {
            throw new IllegalArgumentException("process operation signature mismatch");
        }
    }
}
