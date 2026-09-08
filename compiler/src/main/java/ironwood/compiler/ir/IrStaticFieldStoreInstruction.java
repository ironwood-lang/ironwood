// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStaticFieldStoreInstruction(IrStaticField field, IrOperand value,
                                            SourceSpan sourceSpan) implements IrInstruction {
    public IrStaticFieldStoreInstruction {
        if (!value.type().equals(field.type())) {
            throw new IllegalArgumentException("static field store type mismatch");
        }
    }
}
