// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrUnaryInstruction(IrValueReference result, IrUnaryOperator operator,
                                 IrOperand operand, SourceSpan sourceSpan) implements IrInstruction {
}
