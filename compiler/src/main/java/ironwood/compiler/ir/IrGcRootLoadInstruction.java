// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Development-only load from an explicitly described native stack slot. */
public record IrGcRootLoadInstruction(IrValueReference result, IrValueReference slot,
                                      SourceSpan sourceSpan) implements IrInstruction {
    public IrGcRootLoadInstruction {
        if (!slot.type().equals(IrType.EXCEPTION) || !result.type().isReference()) {
            throw new IllegalArgumentException("GC root load requires native slot and managed result");
        }
    }
}
