// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Compiler-only deallocation used for failed-construction rollback. */
public record IrRawDeallocateInstruction(IrOperand allocation,
                                         SourceSpan sourceSpan)
        implements IrInstruction {
}
