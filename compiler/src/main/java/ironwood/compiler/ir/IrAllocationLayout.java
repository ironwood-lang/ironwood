// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import java.util.List;

/** Exact storage description, resolved again after physical-layout transforms. */
public record IrAllocationLayout(String name, Shape shape, List<IrField> fields,
                                 IrType elementType, String refusal) {
    public enum Shape { FIXED, ARRAY, STRING, REFUSED }

    public IrAllocationLayout {
        fields = List.copyOf(fields);
    }

    /** Include every physical class and array, retaining explicit refusal records. */
    public static List<IrAllocationLayout> of(IrProgram program) {
        return java.util.stream.Stream.concat(program.classes().stream().filter(IrClass::isClass)
                .map(IrAllocationLayout::of), program.arrayTypes().stream().map(IrAllocationLayout::of)).toList();
    }

    public static IrAllocationLayout of(IrClass type) {
        if (!type.isClass()) return refused(type.name(), "interface has no allocation layout");
        if (type.name().equals("ironwood.bridge.ByteView")) {
            return refused(type.name(), "borrowed native descriptor has no managed object header");
        }
        for (int index = 0; index < type.fields().size(); index++) {
            IrField field = type.fields().get(index);
            if (field.layoutIndex() != index) return refused(type.name(), "noncontiguous physical field indices");
            if (field.ownerClass().equals("ironwood.lang.Throwable") && field.name().equals("traceState")) {
                return refused(type.name(), "legacy Throwable.traceState hides native managed links");
            }
            if (field.name().equals("nextCarrier") && field.ownerClass().endsWith("._ForeignFailure")
                    && !field.type().equals(IrType.reference(field.ownerClass()))) {
                return refused(type.name(), "legacy nextCarrier hides a managed link in long");
            }
            if (!supported(field.type())) return refused(type.name(), "unsupported physical field: " + field.name());
        }
        if (type.name().equals("ironwood.lang.String")) {
            if (type.fields().size() != 2
                    || !type.fields().get(0).name().equals("utf16Length")
                    || !type.fields().get(1).name().equals("utf8Length")
                    || type.fields().stream().anyMatch(field -> !field.type().equals(IrType.I32))) {
                return refused(type.name(), "unexpected source String storage");
            }
            return new IrAllocationLayout(type.name(), Shape.STRING, List.of(), IrType.U16, "");
        }
        return new IrAllocationLayout(type.name(), Shape.FIXED, type.fields(), null, "");
    }

    public static IrAllocationLayout of(IrArrayType type) {
        IrType element = type.type().elementType().erasure();
        if (!supported(element)) return refused(type.name(), "unsupported array element storage");
        return new IrAllocationLayout(type.name(), Shape.ARRAY, List.of(), element, "");
    }

    private static boolean supported(IrType type) {
        if (type.erasure().equals(IrType.reference("ironwood.bridge.ByteView"))) return false;
        return switch (type.erasure().kind()) {
            case I1, I8, I16, U16, I32, I64, F32, F64, REFERENCE, ARRAY -> true;
            default -> false;
        };
    }

    private static IrAllocationLayout refused(String name, String reason) {
        return new IrAllocationLayout(name, Shape.REFUSED, List.of(), null, reason);
    }

    public List<IrField> references() {
        return fields.stream().filter(field -> field.type().erasure().isReference()).toList();
    }

    public void requireSupported() {
        if (shape == Shape.REFUSED) throw new IllegalArgumentException("GC2 layout refused for " + name + ": " + refusal);
    }
}
