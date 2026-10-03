// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Bounded failure-only extraction, including live cause nodes; requires its own unwind edge. */
public record IrBridgeFailureSnapshotInstruction(IrOperand exception, IrOperand frameAddress,
                                                  SourceSpan sourceSpan) implements IrInstruction {
    public IrBridgeFailureSnapshotInstruction {
        if ((!exception.type().equals(IrType.EXCEPTION)
                && !exception.type().equals(IrType.reference("ironwood.lang.Throwable")))
                || !frameAddress.type().equals(IrType.I64)) {
            throw new IllegalArgumentException("failure snapshot requires caught exception or live Throwable and native result frame");
        }
    }
}
