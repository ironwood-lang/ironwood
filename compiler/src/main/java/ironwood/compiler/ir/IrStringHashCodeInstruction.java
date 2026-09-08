// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringHashCodeInstruction(IrValueReference result, IrOperand string,
                                          SourceSpan sourceSpan) implements IrInstruction {
    public IrStringHashCodeInstruction {
        if (!result.type().equals(IrType.I32)
                || !string.type().equals(IrType.reference("ironwood.lang.String"))) {
            throw new IllegalArgumentException("String.hashCode requires String and returns int");
        }
    }
}
