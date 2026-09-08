// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringCopyInstruction(IrValueReference result, IrOperand source,
                                      SourceSpan sourceSpan) implements IrInstruction {
    public IrStringCopyInstruction {
        IrType string = IrType.reference("ironwood.lang.String");
        if (!result.type().equals(string) || !source.type().equals(string)) {
            throw new IllegalArgumentException("String copy requires String operands");
        }
    }
}
