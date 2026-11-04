// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import ironwood.compiler.ir.IrAllocationLayout;
import ironwood.compiler.ir.IrType;

import java.util.List;

/** Experimental descriptors only; never called by production LLVM emission. */
public final class GcLayoutEmitter {
    private GcLayoutEmitter() {}

    /** Full-program requests cannot silently omit a refused allocation family. */
    public static String c(ironwood.compiler.ir.IrProgram program) {
        return c(IrAllocationLayout.of(program));
    }

    public static String llvm(ironwood.compiler.ir.IrProgram program) {
        return llvm(IrAllocationLayout.of(program));
    }

    public static String c(List<IrAllocationLayout> layouts) {
        StringBuilder out = new StringBuilder("// SPDX-License-Identifier: MIT OR Apache-2.0\n#include \"ironwood_gc_engine.h\"\n");
        for (int index = 0; index < layouts.size(); index++) {
            var layout = layouts.get(index);
            layout.requireSupported();
            String tag = "iw_shape_" + index;
            out.append("struct ").append(tag).append(" { const void *type;");
            switch (layout.shape()) {
                case FIXED -> layout.fields().forEach(field -> out.append(' ').append(cType(field.type()))
                        .append(" f").append(field.layoutIndex()).append(';'));
                case ARRAY -> out.append(" size_t length, element_size; uint32_t element_kind, reserved; unsigned char data[];");
                case STRING -> out.append(" int32_t length, utf8_length; uint16_t data[];");
                default -> throw new IllegalArgumentException("unsupported layout");
            }
            out.append(" };\n");
            if (!layout.references().isEmpty()) {
                out.append("static const size_t iw_offsets_").append(index).append("[] = {");
                layout.references().forEach(field -> out.append("offsetof(struct ").append(tag).append(", f")
                        .append(field.layoutIndex()).append("),"));
                out.append("};\n");
            }
            out.append("const struct iw_gc_layout iw_c_layout_").append(index).append(" = { 1, ")
                    .append(shape(layout)).append(", 1, ").append(elementKind(layout)).append(", sizeof(struct ")
                    .append(tag).append("), ").append(layout.references().size()).append(", ")
                    .append(layout.references().isEmpty() ? "NULL" : "iw_offsets_" + index).append(" };\n");
        }
        return out.toString();
    }

    public static String llvm(List<IrAllocationLayout> layouts) {
        StringBuilder out = new StringBuilder("; SPDX-License-Identifier: MIT OR Apache-2.0\n%iw.layout = type { i32, i32, i32, i32, i64, i64, ptr }\n");
        for (int index = 0; index < layouts.size(); index++) {
            var layout = layouts.get(index);
            layout.requireSupported();
            String tag = "%iw.shape." + index;
            out.append(tag).append(" = type { ptr");
            switch (layout.shape()) {
                case FIXED -> layout.fields().forEach(field -> out.append(", ").append(llvmType(field.type())));
                case ARRAY -> out.append(", i64, i64, i32, i32, [0 x i8]");
                case STRING -> out.append(", i32, i32, [0 x i16]");
                default -> throw new IllegalArgumentException("unsupported layout");
            }
            out.append(" }\n");
            if (!layout.references().isEmpty()) {
                out.append("@iw.offs.").append(index).append(" = private constant [")
                        .append(layout.references().size()).append(" x i64] [");
                boolean first = true;
                for (var field : layout.references()) {
                    if (!first) out.append(", ");
                    first = false;
                    out.append("i64 ptrtoint (ptr getelementptr (").append(tag)
                            .append(", ptr null, i32 0, i32 ").append(field.layoutIndex() + 1).append(") to i64)");
                }
                out.append("]\n");
            }
            out.append("@iw_llvm_layout_").append(index).append(" = constant %iw.layout { i32 1, i32 ")
                    .append(shape(layout)).append(", i32 1, i32 ").append(elementKind(layout))
                    .append(", i64 ptrtoint (ptr getelementptr (").append(tag)
                    .append(", ptr null, i32 1) to i64), i64 ").append(layout.references().size()).append(", ptr ")
                    .append(layout.references().isEmpty() ? "null" : "@iw.offs." + index).append(" }\n");
        }
        return out.toString();
    }

    private static int shape(IrAllocationLayout layout) {
        return switch (layout.shape()) { case FIXED -> 0; case ARRAY -> 1; case STRING -> 2;
            default -> throw new IllegalArgumentException("unsupported layout"); };
    }

    private static int elementKind(IrAllocationLayout layout) {
        if (layout.shape() != IrAllocationLayout.Shape.ARRAY) return 0;
        return switch (layout.elementType().kind()) {
            case I1 -> 1; case I8 -> 2; case I16 -> 3; case U16 -> 4; case I32 -> 5;
            case I64 -> 6; case F32 -> 7; case F64 -> 8; case REFERENCE, ARRAY -> 9;
            default -> throw new IllegalArgumentException("unsupported array element");
        };
    }

    private static String cType(IrType type) {
        return switch (type.erasure().kind()) {
            case I1 -> "_Bool"; case I8 -> "int8_t"; case I16 -> "int16_t"; case U16 -> "uint16_t";
            case I32 -> "int32_t"; case I64 -> "int64_t"; case F32 -> "float"; case F64 -> "double";
            case REFERENCE, ARRAY -> "void *";
            default -> throw new IllegalArgumentException("unsupported physical type");
        };
    }

    private static String llvmType(IrType type) {
        return switch (type.erasure().kind()) {
            case I1 -> "i1"; case I8 -> "i8"; case I16, U16 -> "i16"; case I32 -> "i32";
            case I64 -> "i64"; case F32 -> "float"; case F64 -> "double"; case REFERENCE, ARRAY -> "ptr";
            default -> throw new IllegalArgumentException("unsupported physical type");
        };
    }
}
