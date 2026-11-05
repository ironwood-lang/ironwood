// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Development-only native stack slot. The stored value is an allocation base. */
public record IrGcRootSlotInstruction(IrValueReference slot, IrOperand value,
                                      SourceSpan sourceSpan) implements IrInstruction {
    public IrGcRootSlotInstruction {
        if (!slot.type().equals(IrType.EXCEPTION) || !value.type().isReference()
                && !value.type().equals(IrType.NULL)) {
            throw new IllegalArgumentException("GC root slot requires native slot and managed value");
        }
    }
}
