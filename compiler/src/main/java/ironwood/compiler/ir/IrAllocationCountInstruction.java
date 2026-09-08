// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Reads the process-wide count of successful Ironwood language allocations. */
public record IrAllocationCountInstruction(IrValueReference result,
                                           SourceSpan sourceSpan) implements IrInstruction {
    public IrAllocationCountInstruction {
        if (!result.type().equals(IrType.I64)) {
            throw new IllegalArgumentException("allocationCount returns long");
        }
    }
}
