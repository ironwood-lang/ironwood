// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrSystemClockInstruction(IrValueReference result, Clock clock,
                                       SourceSpan sourceSpan) implements IrInstruction {
    public IrSystemClockInstruction {
        if (!result.type().equals(IrType.I64)) {
            throw new IllegalArgumentException("System clock result must be long");
        }
    }

    public enum Clock {
        CURRENT_TIME_MILLIS,
        NANO_TIME
    }
}
