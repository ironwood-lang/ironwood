// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** An active-use barrier for one closed-world class or interface. */
public record IrEnsureTypeInitializedInstruction(String typeName, SourceSpan sourceSpan)
        implements IrInstruction {
}
