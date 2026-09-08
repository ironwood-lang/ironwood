// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrSystemExitInstruction(IrOperand status,
                                      SourceSpan sourceSpan) implements IrInstruction {
    public IrSystemExitInstruction {
        if (!status.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("System.exit requires an int status");
        }
    }
}
