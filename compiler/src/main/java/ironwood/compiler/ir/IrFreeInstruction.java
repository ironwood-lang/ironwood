// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrFreeInstruction(IrOperand allocation,
                                SourceSpan sourceSpan) implements IrInstruction {
}
