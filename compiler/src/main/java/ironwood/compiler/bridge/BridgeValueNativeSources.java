// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;

/** JNI value marshalling around proved typed entries; no source semantics live in C. */
public final class BridgeValueNativeSources {
    private static final IrType STRING = IrType.reference("ironwood.lang.String");
    private BridgeValueNativeSources() {}

    /** The bootstrap must initialize iw_exceptions before registering these adapters. */

    static String jniType(IrType type) {
        if (type.equals(STRING)) return "jstring";
        if (ironwood.compiler.semantic.BridgeByteViews.view(type)) return "jobject";
        if (type.isArray()) return "j" + BridgeJavaTypes.sourceName(type.elementType()) + "Array";
        return type.equals(IrType.VOID) ? "void" : "j" + BridgeJavaTypes.sourceName(type);
    }

    static String cType(IrType type) {
        return switch (type.kind()) {
            case I1 -> "uint8_t"; case I8 -> "int8_t"; case I16 -> "int16_t"; case U16 -> "uint16_t";
            case I32 -> "int32_t"; case I64 -> "int64_t"; case F32 -> "float"; case F64 -> "double";
            default -> throw new IllegalArgumentException("unsupported native value carrier");
        };
    }

    static String field(IrType type) {
        return switch (type.kind()) {
            case I1 -> "boolean"; case I8 -> "byte"; case I16 -> "short_integer"; case U16 -> "character";
            case I32 -> "integer"; case I64 -> "wide"; case F32 -> "single"; case F64 -> "real";
            default -> throw new IllegalArgumentException("unsupported native result carrier");
        };
    }
}
