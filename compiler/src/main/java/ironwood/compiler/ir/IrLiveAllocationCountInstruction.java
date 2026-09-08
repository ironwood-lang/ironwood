// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Reads the current process-wide count of live Ironwood allocations. */
public record IrLiveAllocationCountInstruction(IrValueReference result,
                                               SourceSpan sourceSpan)
        implements IrInstruction {
    public IrLiveAllocationCountInstruction {
        if (!result.type().equals(IrType.I64)) {
            throw new IllegalArgumentException("liveAllocationCount returns long");
        }
    }
}
