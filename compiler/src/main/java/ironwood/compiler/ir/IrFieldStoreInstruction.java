// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrFieldStoreInstruction(IrOperand receiver, IrField field, IrOperand value,
                                      SourceSpan sourceSpan) implements IrInstruction {
}
