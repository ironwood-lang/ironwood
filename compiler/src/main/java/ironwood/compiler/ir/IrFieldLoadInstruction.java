// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrFieldLoadInstruction(IrValueReference result, IrOperand receiver,
                                     IrField field, SourceSpan sourceSpan) implements IrInstruction {
}
