// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.BridgeCallbackAdmission;
import ironwood.compiler.ir.IrConstant;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrStringConstant;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Java declarations and matching JNI descriptors, generated from one admitted surface. */
public record BridgeJavaSources(Map<String, String> sources, List<Binding> bindings, List<String> generatedTypes,
                                String ensureMethod, List<FacadeRegistration> facadeRegistrations, List<RootDestruction> rootDestructions,
                                List<NativeDeclaration> supportNatives) {
    private static final String HEADER = "// SPDX-License-Identifier: MIT OR Apache-2.0\n\n";

    public BridgeJavaSources {
        sources = Collections.unmodifiableMap(new TreeMap<>(sources));
        bindings = List.copyOf(bindings);
        generatedTypes = List.copyOf(generatedTypes);
        facadeRegistrations = List.copyOf(facadeRegistrations);
        rootDestructions = List.copyOf(rootDestructions);
        supportNatives = List.copyOf(supportNatives);
    }

    public BridgeJavaSources(Map<String, String> sources, List<Binding> bindings, List<String> generatedTypes,
            String ensureMethod, List<FacadeRegistration> facadeRegistrations, List<RootDestruction> rootDestructions) {
        this(sources, bindings, generatedTypes, ensureMethod, facadeRegistrations, rootDestructions, List.of());
    }

    public BridgeJavaSources(Map<String, String> sources, List<Binding> bindings, List<String> generatedTypes,
            String ensureMethod, List<FacadeRegistration> facadeRegistrations) {
        this(sources, bindings, generatedTypes, ensureMethod, facadeRegistrations, List.of());
    }

    public BridgeJavaSources(Map<String, String> sources, List<Binding> bindings, List<String> generatedTypes, String ensureMethod) {
        this(sources, bindings, generatedTypes, ensureMethod, List.of());
    }

    /** A nonnegative critical index names this binding's additional transition-free native adapter. */
    public record Binding(String binaryName, String nativeName, String descriptor,
                          BridgeApiFacts.Callable method, String entrySymbol, String permanentConversion, Set<Integer> enumTokenParameters,
                          Map<Integer, Integer> fixedEnums, int critical) {
        public Binding {
            enumTokenParameters = Set.copyOf(enumTokenParameters); fixedEnums = Map.copyOf(fixedEnums);
            if (critical < -1) throw new IllegalArgumentException("invalid critical adapter index");
        }
        public Binding(String binaryName, String nativeName, String descriptor,
                BridgeApiFacts.Callable method, String entrySymbol, String permanentConversion, Set<Integer> enumTokenParameters,
                Map<Integer, Integer> fixedEnums) {
            this(binaryName, nativeName, descriptor, method, entrySymbol, permanentConversion, enumTokenParameters, fixedEnums, -1);
        }
        public Binding(String binaryName, String nativeName, String descriptor,
                BridgeApiFacts.Callable method, String entrySymbol, String permanentConversion, Set<Integer> enumTokenParameters) {
            this(binaryName, nativeName, descriptor, method, entrySymbol, permanentConversion, enumTokenParameters, Map.of());
        }
        public Binding(String binaryName, String nativeName, String descriptor,
                BridgeApiFacts.Callable method, String entrySymbol) {
            this(binaryName, nativeName, descriptor, method, entrySymbol, "", Set.of());
        }
        public boolean returnsPermanentAddress() { return !permanentConversion.isEmpty(); }
        public NativeDeclaration conversionDeclaration() {
            if (!returnsPermanentAddress()) throw new IllegalStateException("no permanent result conversion");
            return new NativeDeclaration(binaryName, permanentConversion, "(J)" + BridgeJavaTypes.descriptor(method.result()));
        }
    }

    /** Host-only cache insertion after immutable facade initialization, never a source entry. */
    public record FacadeRegistration(String binaryName, String nativeName) {
        public String descriptor() { return "(JL" + binaryName.replace('.', '/') + ";)V"; }
    }

    /** Host lifetime operation, separate from callable source members and cache delivery. */
    public record RootDestruction(String binaryName, String nativeName, String stateType) {
        public String descriptor() { return "(L" + stateType.replace('.', '/') + ";J)V"; }
    }

    public record NativeDeclaration(String binaryName, String nativeName, String descriptor) {}

    public List<NativeDeclaration> nativeDeclarations() {
        return java.util.stream.Stream.of(bindings.stream().map(binding -> new NativeDeclaration(
                        binding.binaryName(), binding.nativeName(), binding.descriptor())),
                facadeRegistrations.stream().map(binding -> new NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor())),
                rootDestructions.stream().map(binding -> new NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor())),
                bindings.stream().filter(Binding::returnsPermanentAddress).map(Binding::conversionDeclaration).distinct(),
                supportNatives.stream())
                .flatMap(java.util.function.Function.identity())
                .toList();
    }

    public static BridgeJavaSources generate(CompilationArtifact artifact, BridgeExportSurface surface,
            BridgeGeneration generation, BridgeEntryModule module, BridgeExceptionProjection exceptions) {
        return generate(artifact, surface, generation, module, exceptions, null);
    }

    public static BridgeJavaSources generate(CompilationArtifact artifact, BridgeExportSurface surface,
            BridgeGeneration generation, BridgeEntryModule module, BridgeExceptionProjection exceptions,
            BridgeCallbackCarrierEntries carriers) {
        var declarations = generate(artifact, surface, generation, module);
        return exceptions(artifact, generation, exceptions, carriers, declarations);
    }

    public static BridgeJavaSources generateCallbacks(BridgeCallbackAdmission admission, BridgeGeneration generation) {
        if (!generation.matchesCallbacks(admission)) throw new IllegalArgumentException("callback Java identity mismatch");
        var declarations = declarations(admission.artifact(), admission.surface(), generation, admission.entries());
        return BridgeCallbackDispatchSources.add(
                exceptions(admission.artifact(), generation, admission.exceptions().projection(), admission.carriers(), declarations),
                admission.listeners(), generation, admission.surface());
    }

    private static BridgeJavaSources exceptions(CompilationArtifact artifact, BridgeGeneration generation,
            BridgeExceptionProjection exceptions, BridgeCallbackCarrierEntries carriers, BridgeJavaSources declarations) {
        var factory = BridgeExceptionSources.generate(artifact, generation, exceptions, carriers);
        var sources = new TreeMap<>(declarations.sources());
        factory.sources().forEach((name, source) -> {
            if (sources.putIfAbsent(name, source) != null) throw new IllegalArgumentException("exception support source collision: " + name);
        });
        var names = new java.util.TreeSet<>(declarations.generatedTypes());
        for (String name : factory.types()) {
            if (!names.add(name)) throw new IllegalArgumentException("exception support type collision: " + name);
        }
        return new BridgeJavaSources(sources, declarations.bindings(), new ArrayList<>(names), declarations.ensureMethod(),
                declarations.facadeRegistrations(), declarations.rootDestructions());
    }

    public static BridgeJavaSources generate(CompilationArtifact artifact, BridgeExportSurface surface,
                                             BridgeGeneration generation, BridgeEntryModule module) {
        if (!generation.matches(artifact, surface)) throw new IllegalArgumentException("Java source generation identity mismatch");
        return declarations(artifact, surface, generation, module);
    }

    private static BridgeJavaSources declarations(CompilationArtifact artifact, BridgeExportSurface surface,
            BridgeGeneration generation, BridgeEntryModule module) {
        var entrySymbols = module.entries().stream().collect(Collectors.toMap(
                entry -> entry.root().callable(), entry -> entry.function().linkageName()));
        if (!entrySymbols.keySet().equals(surface.roots().roots().stream().map(BridgeRootSet.Root::callable).collect(Collectors.toSet()))
                || !module.matchesOriginal(artifact)) {
            throw new IllegalArgumentException("Java declarations require matching proved typed entries");
        }
        String support = generation.supportPackage();
        if (surface.types().stream().anyMatch(type -> type.packageName().equals(support))) {
            throw new IllegalArgumentException("export package collides with the reserved artifact support namespace");
        }
        var sources = new TreeMap<String, String>();
        var bindings = new ArrayList<Binding>();
        var names = new java.util.TreeSet<String>();
        var methodNames = surface.types().stream().flatMap(type -> type.callables().stream())
                .map(BridgeApiFacts.Callable::name).collect(Collectors.toSet());
        String ensure = "$ironwood$ensure";
        while (methodNames.contains(ensure)) ensure += "$";
        String annotation = "@" + support + ".Identity(" + quote(generation.identity()) + ")\n";
        for (var type : surface.types()) {
            names.add(type.binaryName());
            if (type.enclosingType().isPresent()) continue;
            var text = new StringBuilder(HEADER).append("package ").append(type.packageName()).append(";\n\n")
                    .append("import static ").append(support).append(".Support.").append(ensure).append(";\n\n");
            emitType(text, type, surface, entrySymbols, bindings, annotation, ensure, "");
            sources.put(type.binaryName().replace('.', '/') + ".java", text.toString());
        }
        for (String name : surface.types().stream().map(BridgeApiFacts.Type::packageName).distinct().sorted().toList()) {
            String binaryName = name + "." + BridgeExportSurface.PACKAGE_MARKER;
            names.add(binaryName);
            sources.put(binaryName.replace('.', '/') + ".java", HEADER + "package " + name + ";\n\n"
                    + annotation + "public final class " + BridgeExportSurface.PACKAGE_MARKER + " {\n"
                    + "    private " + BridgeExportSurface.PACKAGE_MARKER + "() {}\n}\n");
        }
        names.add(support + ".Identity");
        names.add(support + ".Support");
        sources.put(support.replace('.', '/') + "/Identity.java", HEADER + "package " + support + ";\n\n"
                + annotation + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                + "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)\n"
                + "public @interface Identity { String value(); }\n");
        // The complete loader source is supplied by its separate generator. There
        // is deliberately no fallback or inert Support class in production.
        return new BridgeJavaSources(sources, bindings, new ArrayList<>(names), ensure);
    }

    private static void emitType(StringBuilder text, BridgeApiFacts.Type type, BridgeExportSurface surface,
            Map<BridgeCallableId, String> entries, List<Binding> bindings, String annotation, String ensure, String indent) {
        String simpleName = type.sourceName().substring(type.sourceName().lastIndexOf('.') + 1);
        if (type.kind() == BridgeApiFacts.Kind.INTERFACE) {
            text.append(indent).append(annotation).append(indent).append("public interface ").append(simpleName).append(" {\n");
            for (var method : type.callables()) {
                if (method.owner().equals("ironwood.lang.Object")) continue;
                text.append(indent).append("    ").append(BridgeJavaTypes.sourceName(method.result())).append(' ')
                        .append(method.name()).append('(');
                for (int index = 0; index < method.parameters().size(); index++) {
                    if (index > 0) text.append(", ");
                    text.append(BridgeJavaTypes.sourceName(method.parameters().get(index))).append(' ').append(method.parameterNames().get(index));
                }
                text.append(')');
                if (!method.thrownTypes().isEmpty()) text.append(" throws ").append(method.thrownTypes().stream()
                        .map(BridgeJavaTypes::sourceName).collect(Collectors.joining(", ")));
                text.append(";\n");
            }
            text.append(indent).append("}\n");
            return;
        }
        text.append(indent).append(annotation).append(indent).append("public ")
                .append(type.enclosingType().isPresent() ? "static " : "").append("final class ").append(simpleName).append(" {\n")
                .append(indent).append("    private ").append(simpleName).append("() {}\n")
                .append(indent).append("    static { ").append(ensure).append("(); }\n");
        for (var field : type.fields()) {
            text.append(indent).append("    public static final ").append(BridgeJavaTypes.sourceName(field.type()))
                    .append(' ').append(field.name()).append(" = ").append(literal(field.constant().orElseThrow())).append(";\n");
        }
        var occupied = type.callables().stream().map(BridgeApiFacts.Callable::name).collect(Collectors.toCollection(HashSet::new));
        int nextName = 0;
        for (var method : type.callables()) {
            if (method.owner().equals("ironwood.lang.Object")) continue;
            String nativeName;
            do { nativeName = "$ironwood$native$" + nextName++; } while (!occupied.add(nativeName));
            var parameters = new ArrayList<String>();
            for (int index = 0; index < method.parameters().size(); index++) {
                parameters.add(sourceName(method.parameters().get(index), surface) + " " + method.parameterNames().get(index));
            }
            String formals = String.join(", ", parameters);
            String result = BridgeJavaTypes.sourceName(method.result());
            String throwsClause = method.thrownTypes().isEmpty() ? "" : " throws " + method.thrownTypes().stream()
                    .map(BridgeJavaTypes::sourceName).collect(Collectors.joining(", "));
            String descriptor = "(" + method.parameters().stream().map(BridgeJavaTypes::descriptor).collect(Collectors.joining())
                    + ")" + BridgeJavaTypes.descriptor(method.result());
            text.append(indent).append("    public static ").append(result).append(' ').append(method.name())
                    .append('(').append(formals).append(')').append(throwsClause).append(" {\n")
                    .append(indent).append("        ").append(method.result().equals(IrType.VOID) ? "" : "return ")
                    .append(nativeName).append('(').append(String.join(", ", method.parameterNames())).append(");\n")
                    .append(indent).append("    }\n")
                    .append(indent).append("    private static native ").append(result).append(' ').append(nativeName)
                    .append('(').append(formals).append(')').append(throwsClause).append(";\n");
            bindings.add(new Binding(type.binaryName(), nativeName, descriptor, method, entries.get(method.target().orElseThrow())));
        }
        for (var nested : surface.types()) {
            if (nested.enclosingType().filter(type.binaryName()::equals).isPresent()) {
                emitType(text, nested, surface, entries, bindings, annotation, ensure, indent + "    ");
            }
        }
        text.append(indent).append("}\n");
    }

    private static String sourceName(IrType type, BridgeExportSurface surface) {
        if (type.isNominalReference()) {
            var declaration = surface.types().stream().filter(candidate -> candidate.binaryName().equals(type.referenceName())).findFirst();
            if (declaration.isPresent()) return declaration.orElseThrow().sourceName();
        }
        return BridgeJavaTypes.sourceName(type);
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
