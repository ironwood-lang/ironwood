// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrIdentityHashCodeInstruction(IrValueReference result, IrOperand object,
                                            SourceSpan sourceSpan) implements IrInstruction {
    public IrIdentityHashCodeInstruction {
        if (!result.type().equals(IrType.I32) || !object.type().isReference()) {
            throw new IllegalArgumentException("identityHashCode requires a reference and returns int");
        }
    }
}
