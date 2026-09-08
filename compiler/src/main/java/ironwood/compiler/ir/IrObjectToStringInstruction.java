// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrObjectToStringInstruction(IrValueReference result, IrOperand object,
                                          SourceSpan sourceSpan) implements IrInstruction {
    public IrObjectToStringInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !object.type().isNominalReference()) {
            throw new IllegalArgumentException("Object.toString requires an object and returns String");
        }
    }
}
