// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringEqualsInstruction(IrValueReference result, IrOperand string,
                                        IrOperand other,
                                        SourceSpan sourceSpan) implements IrInstruction {
    public IrStringEqualsInstruction {
        if (!result.type().equals(IrType.I1)
                || !string.type().equals(IrType.reference("ironwood.lang.String"))
                || !other.type().equals(IrType.reference("ironwood.lang.Object"))) {
            throw new IllegalArgumentException("String.equals requires String and Object and returns boolean");
        }
    }
}
