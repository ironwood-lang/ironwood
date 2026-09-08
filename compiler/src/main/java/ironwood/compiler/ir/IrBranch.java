// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrBranch(IrOperand condition, String trueTarget, String falseTarget,
                       SourceSpan sourceSpan) implements IrTerminator {
}
