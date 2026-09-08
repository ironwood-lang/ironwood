// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrBinaryInstruction(IrValueReference result, IrBinaryOperator operator,
                                  IrOperand left, IrOperand right,
                                  SourceSpan sourceSpan) implements IrInstruction {
}
