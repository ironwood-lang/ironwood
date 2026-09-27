// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/**
 * Compiler-only write into the adapter's invocation-local, 16-byte result frame.
 * The adapter supplies an aligned live address; ordinary source cannot create
 * this operation. The value slot is eight bytes, followed by a throwable pointer.
 */
public record IrBridgeResultStoreInstruction(IrOperand frameAddress, Slot slot,
                                              IrOperand value, SourceSpan sourceSpan) implements IrInstruction {
    public enum Slot { VALUE, EXCEPTION }

    public IrBridgeResultStoreInstruction {
        if (!frameAddress.type().equals(IrType.I64) || slot == null
                || (slot == Slot.EXCEPTION ? !value.type().equals(IrType.EXCEPTION)
                    : !value.type().isNumeric() && !value.type().equals(IrType.I1))) {
            throw new IllegalArgumentException("invalid bridge result store");
        }
    }
}
