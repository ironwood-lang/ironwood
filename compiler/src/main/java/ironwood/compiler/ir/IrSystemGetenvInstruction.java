// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrSystemGetenvInstruction(IrValueReference result, IrOperand name,
                                        SourceSpan sourceSpan) implements IrInstruction {
    public IrSystemGetenvInstruction {
        IrType string = IrType.reference("ironwood.lang.String");
        if (!result.type().equals(string) || !name.type().equals(string)) {
            throw new IllegalArgumentException("System.getenv requires String operands");
        }
    }
}
