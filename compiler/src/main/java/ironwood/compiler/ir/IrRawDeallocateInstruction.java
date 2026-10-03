// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Compiler-only deallocation for rollback or proved temporary storage without destructors. */
public record IrRawDeallocateInstruction(IrOperand allocation,
                                         SourceSpan sourceSpan)
        implements IrInstruction {
}
