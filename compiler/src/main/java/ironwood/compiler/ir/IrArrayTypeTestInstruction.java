// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Tests exact recursively erased array descriptor identity. */
public record IrArrayTypeTestInstruction(IrValueReference result, IrOperand value,
                                         IrType targetType,
                                         SourceSpan sourceSpan) implements IrInstruction {
    public IrArrayTypeTestInstruction {
        if (!result.type().equals(IrType.I1)) {
            throw new IllegalArgumentException("array type-test result must have type boolean");
        }
        if (!value.type().isReference() && !value.type().equals(IrType.NULL)) {
            throw new IllegalArgumentException("array type-test value must be a reference or null");
        }
        if (!targetType.isArray() || !targetType.equals(targetType.erasure())) {
            throw new IllegalArgumentException("array type-test target must be a reified array erasure");
        }
    }
}
