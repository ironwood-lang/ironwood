// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Releases a toString result only when its concrete dispatch target returns owned fresh text. */
public record IrReleaseOwnedToStringResultInstruction(IrOperand object, IrOperand result,
                                                       SourceSpan sourceSpan)
        implements IrInstruction {
}
