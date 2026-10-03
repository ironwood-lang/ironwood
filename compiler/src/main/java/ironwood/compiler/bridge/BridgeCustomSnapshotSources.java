// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Java-only copied throwable declarations; source constructors are never invoked. */
final class BridgeCustomSnapshotSources {
    record Context(BridgeExceptionProjection projection, BridgeCustomSnapshotLayout layout, String dataType) {}
    private BridgeCustomSnapshotSources() {}

    static void emit(StringBuilder text, BridgeApiFacts.Type type, BridgeExportSurface surface,
            Context context, String annotation, String indent) {
        if (!context.projection().customTypes().containsKey(type.binaryName())) throw new IllegalArgumentException("snapshot declaration missing from exact projection");
        String simple = type.sourceName().substring(type.sourceName().lastIndexOf('.') + 1);
        var parent = type.supertypes().getFirst();
        var occupied = type.callables().stream().map(BridgeApiFacts.Callable::name).collect(Collectors.toCollection(HashSet::new));
        type.fields().forEach(field -> occupied.add(field.name()));
        type.callables().forEach(method -> occupied.addAll(method.parameterNames()));
        String data = BridgePermanentJavaSources.unique(occupied, "$ironwood$snapshot");
        text.append(indent).append(annotation).append(indent).append("@SuppressWarnings(\"serial\")\n")
                .append(indent).append("public ").append(type.enclosingType().isPresent() ? "static " : "")
                .append(type.abstractType() ? "abstract " : type.finalType() ? "final " : "").append("class ")
                .append(simple).append(" extends ").append(BridgePermanentJavaSources.javaType(parent, surface)).append(" {\n")
                .append(indent).append("    private final ").append(context.dataType()).append(' ').append(data).append(";\n")
                .append(indent).append("    ").append(type.finalType() ? "private " : "protected ").append(simple)
                .append('(').append(context.dataType()).append(" data) {\n")
                .append(indent).append("        super(").append(context.projection().customTypes().containsKey(parent.referenceName())
                        ? "data" : constructor(parent.referenceName())).append(");\n")
                .append(indent).append("        this.").append(data).append(" = data;\n")
                .append(indent).append("    }\n");
        for (var field : type.fields()) {
            if (BridgeExportSurface.isBuiltinThrowable(IrType.reference(field.owner()))) continue;
            text.append(indent).append("    public static final ").append(BridgePermanentJavaSources.javaType(field.type(), surface))
                    .append(' ').append(field.name()).append(" = ").append(BridgeJavaSources.literal(field.constant().orElseThrow())).append(";\n");
        }
        var properties = context.projection().types().stream().filter(value -> value.nativeName().equals(type.binaryName()))
                .findFirst().map(BridgeExceptionProjection.Type::properties).orElse(List.of());
        for (var method : type.callables()) {
            boolean graph = Set.of("getMessage", "getCause").contains(method.name()) && method.parameters().isEmpty();
            boolean custom = BridgeCustomExceptionTypes.customMethod(method);
            boolean constructorData = properties.stream().anyMatch(property -> property.name().equals(method.name())
                    && property.type().equals(method.result()) && !Set.of("getSecondaryExceptionCount", "getSecondaryException").contains(property.name()));
            if (!graph && !custom && !constructorData) continue;
            String value;
            if (method.name().equals("getMessage")) value = "this." + data + ".message()";
            else if (method.name().equals("getCause")) value = castThrowable(method.result(), surface) + "this." + data + ".cause()";
            else if (method.name().equals("getSecondaryExceptionCount")) value = "this." + data + ".secondaryCount()";
            else if (method.name().equals("getSecondaryException")) value = castThrowable(method.result(), surface)
                    + "this." + data + ".secondary(" + method.parameterNames().getFirst() + ")";
            else {
                var slot = context.layout().slot(method.name(), method.result());
                String bits = "this." + data + ".bits(" + slot.index() + ")";
                value = switch (method.result().kind()) {
                    case I1 -> bits + " != 0";
                    case I8 -> "(byte) " + bits;
                    case I16 -> "(short) " + bits;
                    case U16 -> "(char) " + bits;
                    case I32 -> "(int) " + bits;
                    case I64 -> bits;
                    case F32 -> "java.lang.Float.intBitsToFloat((int) " + bits + ")";
                    case F64 -> "java.lang.Double.longBitsToDouble(" + bits + ")";
                    case REFERENCE -> "this." + data + ".text(" + slot.index() + ")";
                    default -> throw new IllegalArgumentException("unsupported copied snapshot getter");
                };
            }
            var formals = new java.util.ArrayList<String>();
            for (int index = 0; index < method.parameters().size(); index++) formals.add(BridgePermanentJavaSources.javaType(method.parameters().get(index), surface)
                    + " " + method.parameterNames().get(index));
            String thrown = method.thrownTypes().isEmpty() ? "" : " throws " + method.thrownTypes().stream()
                    .map(exception -> BridgePermanentJavaSources.javaType(exception, surface)).collect(Collectors.joining(", "));
            text.append(indent).append("    public ").append(BridgePermanentJavaSources.javaType(method.result(), surface)).append(' ')
                    .append(method.name()).append('(').append(String.join(", ", formals)).append(')').append(thrown)
                    .append(" { return ").append(value).append("; }\n");
        }
    }

    private static String castThrowable(IrType type, BridgeExportSurface surface) {
        return type.equals(IrType.reference("ironwood.lang.Throwable")) ? ""
                : "(" + BridgePermanentJavaSources.javaType(type, surface) + ") ";
    }

    private static String constructor(String parent) {
        // User overrides may return values that the Java superclass constructor
        // rejects. Its private initial state is not the snapshot: generated
        // accessors expose the separately captured native data instead.
        return switch (parent) {
            case "ironwood.nio.InvalidMarkException", "ironwood.nio.BufferUnderflowException",
                    "ironwood.nio.BufferOverflowException", "ironwood.nio.file.ClosedDirectoryStreamException" -> "";
            case "ironwood.nio.file.FileSystemException", "ironwood.nio.file.FileAlreadyExistsException",
                    "ironwood.nio.file.NoSuchFileException", "ironwood.nio.file.AccessDeniedException" -> "null, null, null";
            case "ironwood.nio.file.InvalidPathException", "ironwood.time.format.DateTimeParseException" -> "\"\", \"\", -1";
            case "ironwood.io.UncheckedIOException" -> "\"\", data.constructionCause()";
            default -> {
                if (!BridgeExportSurface.builtinThrowableNames().contains(parent)) throw new IllegalArgumentException("unmapped custom snapshot superclass");
                yield "\"\"";
            }
        };
    }

    static String factory(Context context) {
        var text = new StringBuilder();
        var concrete = context.projection().types().stream().filter(type -> context.projection().customTypes().containsKey(type.nativeName())).toList();
        for (var type : concrete) {
            text.append("    private static final java.lang.reflect.Constructor<?> snapshotConstructor").append(type.typeId())
                    .append(" = snapshotConstructor(").append(context.projection().customTypes().get(type.nativeName()).sourceName()).append(".class);\n");
        }
        text.append("    private static boolean customType(int type) {\n        ")
                .append(concrete.isEmpty() ? "return false;" : "return switch (type) { case " + concrete.stream().map(type -> Integer.toString(type.typeId()))
                        .collect(Collectors.joining(", ")) + " -> true; default -> false; };")
                .append("\n    }\n")
                .append("    private static SnapshotData snapshotData(int type, String message, long[] numbers, String[] strings) {\n")
                .append("        if (!customType(type)) return null;\n")
                .append("        if (numbers == null || strings == null || numbers.length != ").append(context.layout().slots().size())
                .append(" || strings.length != ").append(context.layout().slots().size()).append(") throw invalidGraph();\n")
                .append("        return new SnapshotData(message, numbers, strings);\n    }\n")
                .append("    private static void validateSnapshot(int type, SnapshotData data) {\n        switch (type) {\n");
        for (var type : concrete) {
            text.append("        case ").append(type.typeId()).append(":\n");
            for (var method : context.projection().customTypes().get(type.nativeName()).callables()) {
                boolean cause = method.name().equals("getCause") && method.parameters().isEmpty();
                boolean secondary = method.name().equals("getSecondaryException") && method.parameters().equals(List.of(IrType.I32));
                if ((!cause && !secondary) || method.result().equals(IrType.reference("ironwood.lang.Throwable"))) continue;
                String expected = context.projection().customTypes().containsKey(method.result().referenceName())
                        ? context.projection().customTypes().get(method.result().referenceName()).sourceName() : BridgeJavaTypes.sourceName(method.result());
                if (cause) text.append("            if (data.cause != null && !(data.cause instanceof ").append(expected).append(")) throw invalidGraph();\n");
                else text.append("            for (Throwable value : data.secondary) if (!(value instanceof ").append(expected).append(")) throw invalidGraph();\n");
            }
            text.append("            return;\n");
        }
        text.append("        default: throw invalidGraph();\n        }\n    }\n");
        text.append("""
                    private static java.lang.reflect.Constructor<?> snapshotConstructor(Class<? extends Throwable> type) {
                        try {
                            var constructor = type.getDeclaredConstructor(SnapshotData.class);
                            constructor.setAccessible(true);
                            return constructor;
                        } catch (ReflectiveOperationException failure) {
                            throw new LinkageError("invalid generated snapshot constructor", failure);
                        }
                    }
                    private static Throwable construct(java.lang.reflect.Constructor<?> constructor, SnapshotData data) {
                        try { return (Throwable) constructor.newInstance(data); }
                        catch (java.lang.reflect.InvocationTargetException failure) {
                            Throwable cause = failure.getCause();
                            if (cause instanceof Error error) throw error;
                            if (cause instanceof RuntimeException error) throw error;
                            throw new LinkageError("generated snapshot constructor failed", cause);
                        } catch (ReflectiveOperationException failure) {
                            throw new LinkageError("invalid generated snapshot construction", failure);
                        }
                    }
                """);
        return text.toString();
    }

    static BridgeExceptionSources.Sources data(BridgeGeneration generation) {
        String name = generation.supportPackage() + ".SnapshotData";
        String source = DATA.replace("@PACKAGE@", generation.supportPackage()).replace("@GENERATION@", generation.identity());
        return new BridgeExceptionSources.Sources(Map.of(name.replace('.', '/') + ".java", source), List.of(name));
    }

    private static final String DATA = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            package @PACKAGE@;

            @Identity("@GENERATION@")
            public final class SnapshotData implements java.io.Serializable {
                private static final long serialVersionUID = 1L;
                private final String message;
                private final long[] numbers;
                private final String[] strings;
                Throwable cause;
                Throwable[] secondary;

                SnapshotData(String message, long[] numbers, String[] strings) {
                    this.message = message; this.numbers = numbers; this.strings = strings;
                }
                public String message() { return message; }
                public long bits(int slot) { return numbers[slot]; }
                public String text(int slot) { return strings[slot]; }
                public Throwable cause() { return cause; }
                public int secondaryCount() { return secondary.length; }
                public Throwable secondary(int index) { return secondary[index]; }
                public java.io.IOException constructionCause() {
                    return cause instanceof java.io.IOException value ? value : new java.io.IOException();
                }
            }
            """;
}
