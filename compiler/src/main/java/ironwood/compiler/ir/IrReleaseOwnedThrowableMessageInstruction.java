// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Releases localized text only when its concrete getter is proven to return owned fresh text. */
public record IrReleaseOwnedThrowableMessageInstruction(IrOperand throwable, IrOperand message,
                                                        SourceSpan sourceSpan)
        implements IrInstruction {
}
