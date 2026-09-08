// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Consumes the distinct owned elements of a proven creation array, leaving null slots. */
public record IrDestroyArrayElementsInstruction(IrOperand array,
                                                SourceSpan sourceSpan) implements IrInstruction {
    public IrDestroyArrayElementsInstruction {
        if (!array.type().isArray() || !array.type().elementType().isReference()) {
            throw new IllegalArgumentException("owned-element destruction requires a reference array");
        }
    }
}
