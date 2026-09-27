// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;

/** Java spelling and class-file descriptors for already validated signature types. */
public final class BridgeJavaTypes {
    private BridgeJavaTypes() {}

    public static String binaryName(IrType type) {
        if (!type.isNominalReference() || !type.typeArguments().isEmpty()) {
            throw new IllegalArgumentException("not a nominal Java Bridge type: " + type.displayName());
        }
        if (type.referenceName().equals("ironwood.lang.String") || type.referenceName().equals("ironwood.lang.Object")
                || BridgeExportSurface.isBuiltinThrowable(type)) {
            return "java." + type.referenceName().substring("ironwood.".length());
        }
        return type.referenceName();
    }

    public static String sourceName(IrType type) {
        return switch (type.kind()) {
            case VOID -> "void";
            case I1 -> "boolean";
            case I8 -> "byte";
            case I16 -> "short";
            case U16 -> "char";
            case I32 -> "int";
            case I64 -> "long";
            case F32 -> "float";
            case F64 -> "double";
            case REFERENCE -> {
                String mapped = binaryName(type);
                if (mapped.equals(type.referenceName())) {
                    throw new IllegalArgumentException("source spelling requires resolved nominal metadata: " + mapped);
                }
                yield mapped;
            }
            default -> throw new IllegalArgumentException("unsupported Java Bridge type: " + type.displayName());
        };
    }

    public static String descriptor(IrType type) {
        return switch (type.kind()) {
            case VOID -> "V";
            case I1 -> "Z";
            case I8 -> "B";
            case I16 -> "S";
            case U16 -> "C";
            case I32 -> "I";
            case I64 -> "J";
            case F32 -> "F";
            case F64 -> "D";
            case REFERENCE -> "L" + binaryName(type).replace('.', '/') + ";";
            default -> throw new IllegalArgumentException("unsupported Java Bridge descriptor: " + type.displayName());
        };
    }
}
