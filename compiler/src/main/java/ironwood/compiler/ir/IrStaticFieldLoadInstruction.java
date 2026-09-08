// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStaticFieldLoadInstruction(IrValueReference result, IrStaticField field,
                                           SourceSpan sourceSpan) implements IrInstruction {
    public IrStaticFieldLoadInstruction {
        if (!result.type().equals(field.type())) {
            throw new IllegalArgumentException("static field load type mismatch");
        }
    }
}
