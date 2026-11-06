// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrConstant;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrStringConstant;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Java declarations and matching JNI descriptors, generated from one admitted surface. */
public record BridgeJavaSources(Map<String, String> sources, List<Binding> bindings, List<String> generatedTypes,
                                String ensureMethod, List<NativeDeclaration> supportNatives) {
    public BridgeJavaSources {
        sources = Collections.unmodifiableMap(new TreeMap<>(sources));
        bindings = List.copyOf(bindings);
        generatedTypes = List.copyOf(generatedTypes);
        supportNatives = List.copyOf(supportNatives);
    }

    /** A nonnegative critical index names an additional transition-free adapter. */
    public record Binding(String binaryName, String nativeName, String descriptor,
                          BridgeApiFacts.Callable method, String entrySymbol, Set<Integer> enumTokenParameters,
                          Map<Integer, Integer> fixedEnums, int critical) {
        public Binding {
            enumTokenParameters = Set.copyOf(enumTokenParameters);
            fixedEnums = Map.copyOf(fixedEnums);
            if (critical < -1) throw new IllegalArgumentException("invalid critical adapter index");
        }
        public Binding(String binaryName, String nativeName, String descriptor,
                BridgeApiFacts.Callable method, String entrySymbol, Set<Integer> enumTokenParameters) {
            this(binaryName, nativeName, descriptor, method, entrySymbol, enumTokenParameters, Map.of(), -1);
        }
        public Binding(String binaryName, String nativeName, String descriptor,
                BridgeApiFacts.Callable method, String entrySymbol) {
            this(binaryName, nativeName, descriptor, method, entrySymbol, Set.of());
        }
    }

    public record NativeDeclaration(String binaryName, String nativeName, String descriptor) {}

    public List<NativeDeclaration> nativeDeclarations() {
        return java.util.stream.Stream.concat(bindings.stream().map(binding ->
                new NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor())), supportNatives.stream()).toList();
    }

    static String literal(IrOperand value) {
        if (value instanceof IrStringConstant string) return quote(string.value());
        if (!(value instanceof IrConstant number)) throw new IllegalArgumentException("unsupported Java constant");
        return switch (number.type().kind()) {
            case I1 -> number.value().intValue() == 0 ? "false" : "true";
            case I8 -> "(byte) " + number.value().byteValue();
            case I16 -> "(short) " + number.value().shortValue();
            case U16 -> "(char) " + number.value().intValue();
            case I32 -> Integer.toString(number.value().intValue());
            case I64 -> number.value().longValue() + "L";
            case F32 -> {
                float real = number.value().floatValue();
                yield Float.isNaN(real) ? "(0.0f / 0.0f)" : real == Float.POSITIVE_INFINITY ? "(1.0f / 0.0f)"
                        : real == Float.NEGATIVE_INFINITY ? "(-1.0f / 0.0f)" : Float.toHexString(real) + "f";
            }
            case F64 -> {
                double real = number.value().doubleValue();
                yield Double.isNaN(real) ? "(0.0d / 0.0d)" : real == Double.POSITIVE_INFINITY ? "(1.0d / 0.0d)"
                        : real == Double.NEGATIVE_INFINITY ? "(-1.0d / 0.0d)" : Double.toHexString(real);
            }
            default -> throw new IllegalArgumentException("unsupported primitive Java constant");
        };
    }

    public static String quote(String value) {
        var text = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                case '\b' -> text.append("\\b");
                case '\f' -> text.append("\\f");
                default -> {
                    if (character < 32 || character == 127) text.append(String.format(java.util.Locale.ROOT, "\\%03o", (int) character));
                    else if (character > 126) text.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    else text.append(character);
                }
            }
        }
        return text.append('"').toString();
    }
}
