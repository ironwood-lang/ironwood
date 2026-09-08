// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Typed native conversion of a String already accepted by the Java grammar validator. */
public record IrFloatingParseInstruction(IrValueReference result, IrOperand text,
                                         SourceSpan sourceSpan) implements IrInstruction {
    public IrFloatingParseInstruction {
        if (!result.type().equals(IrType.F32) && !result.type().equals(IrType.F64)) {
            throw new IllegalArgumentException("floating parse result must be float or double");
        }
        if (!text.type().equals(IrType.reference("ironwood.lang.String"))) {
            throw new IllegalArgumentException("floating parse input must be String");
        }
    }
}
