// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrType;

/** Central source-language primitive promotion and widening rules. */
final class PrimitiveConversions {
    private PrimitiveConversions() {
    }

    static boolean canWiden(IrType target, IrType source) {
        if (target.equals(source)) {
            return true;
        }
        if (!target.isNumeric() || !source.isNumeric()) {
            return false;
        }
        return switch (source.kind()) {
            case I8 -> target.equals(IrType.I16) || target.equals(IrType.I32)
                    || target.equals(IrType.I64) || target.equals(IrType.F32)
                    || target.equals(IrType.F64);
            case I16 -> target.equals(IrType.I32) || target.equals(IrType.I64)
                    || target.equals(IrType.F32) || target.equals(IrType.F64);
            case U16 -> target.equals(IrType.I32) || target.equals(IrType.I64)
                    || target.equals(IrType.F32) || target.equals(IrType.F64);
            case I32 -> target.equals(IrType.I64) || target.equals(IrType.F32)
                    || target.equals(IrType.F64);
            case I64 -> target.equals(IrType.F32) || target.equals(IrType.F64);
            case F32 -> target.equals(IrType.F64);
            default -> false;
        };
    }

    static IrType unaryPromotion(IrType type) {
        if (type.equals(IrType.I8) || type.equals(IrType.I16) || type.equals(IrType.U16)) {
            return IrType.I32;
        }
        return type;
    }

    static IrType binaryPromotion(IrType left, IrType right) {
        if (left.equals(IrType.F64) || right.equals(IrType.F64)) {
            return IrType.F64;
        }
        if (left.equals(IrType.F32) || right.equals(IrType.F32)) {
            return IrType.F32;
        }
        if (left.equals(IrType.I64) || right.equals(IrType.I64)) {
            return IrType.I64;
        }
        return IrType.I32;
    }
}
