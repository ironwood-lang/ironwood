// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrObjectHashCodeInstruction(IrValueReference result, IrOperand object,
                                          SourceSpan sourceSpan) implements IrInstruction {
    public IrObjectHashCodeInstruction {
        if (!result.type().equals(IrType.I32) || !object.type().isNominalReference()) {
            throw new IllegalArgumentException("object hashCode requires an object and returns int");
        }
    }
}
