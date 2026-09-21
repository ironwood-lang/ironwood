// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Read-only state-2 test. Never initiates initialization or implies state-1 publication. */
public record IrTypeInitializedInstruction(IrValueReference result, String typeName,
                                           SourceSpan sourceSpan) implements IrInstruction {
    public IrTypeInitializedInstruction {
        if (!result.type().equals(IrType.I1)) {
            throw new IllegalArgumentException("initialized-state test requires boolean result");
        }
    }
}
