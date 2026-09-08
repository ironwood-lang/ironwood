// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrSystemPropertyInstruction(IrValueReference result, IrOperand name,
                                          SourceSpan sourceSpan) implements IrInstruction {
    public IrSystemPropertyInstruction {
        IrType string = IrType.reference("ironwood.lang.String");
        if (!result.type().equals(string) || !name.type().equals(string)) {
            throw new IllegalArgumentException("System.getProperty requires String operands");
        }
    }
}
