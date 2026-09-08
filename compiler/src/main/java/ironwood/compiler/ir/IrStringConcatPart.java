// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import java.util.Objects;

/** One already-evaluated input to a dynamic String concatenation. */
public record IrStringConcatPart(IrStringConcatPartKind kind, IrOperand value) {
    public IrStringConcatPart {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        boolean valid = switch (kind) {
            case STRING -> value.type().equals(IrType.reference("ironwood.lang.String"));
            case BOOLEAN -> value.type().equals(IrType.I1);
            case CHARACTER -> value.type().equals(IrType.U16);
            case INTEGER -> value.type().isIntegral() && !value.type().equals(IrType.U16);
            case FLOAT -> value.type().equals(IrType.F32);
            case DOUBLE -> value.type().equals(IrType.F64);
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid " + kind
                    + " String-concatenation operand " + value.type().displayName());
        }
    }
}
