// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Recursively rolls back compiler-proven-owned state of an incomplete value. */
public record IrRollbackInstruction(IrOperand allocation,
                                    SourceSpan sourceSpan) implements IrInstruction {
}
