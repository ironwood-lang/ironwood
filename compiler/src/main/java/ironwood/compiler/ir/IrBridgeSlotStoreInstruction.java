// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Bounded final-slot payload after the private result prefix and failure snapshot. */
public record IrBridgeSlotStoreInstruction(IrOperand frameAddress, int index, IrOperand holder,
                                           IrOperand value, SourceSpan sourceSpan) implements IrInstruction {
    public static final int FRAME_PREFIX_BYTES = 288;
    public static final int RECORD_BYTES = 16;

    public IrBridgeSlotStoreInstruction {
        if (!frameAddress.type().equals(IrType.I64) || index < 0
                || !holder.type().isReference() || !value.type().isReference()) {
            throw new IllegalArgumentException("invalid bridge slot payload");
        }
    }
}
