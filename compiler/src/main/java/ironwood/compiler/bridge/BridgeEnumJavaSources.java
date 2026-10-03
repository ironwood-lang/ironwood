// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Java enum metadata is independent of native initialization and conversion. */
final class BridgeEnumJavaSources {
    private BridgeEnumJavaSources() {}

    static BridgePermanentJavaSources.EnumFacade emit(StringBuilder text, BridgeApiFacts.Type type, CompilationArtifact artifact,
            BridgeObjectAdmission admission, String stateType, Map<BridgeCallableId, String> entries, List<BridgeJavaSources.Binding> bindings,
            String annotation, String ensure, String indent) {
        var surface = admission.surface();
        var declared = IrType.reference(type.binaryName());
        var constants = BridgeEnumConstants.discover(artifact, Set.of(declared));
        var values = constants.constants().get(declared);
        var occupied = type.callables().stream().map(BridgeApiFacts.Callable::name).collect(Collectors.toCollection(HashSet::new));
        type.fields().forEach(field -> occupied.add(field.name()));
        type.callables().forEach(method -> occupied.addAll(method.parameterNames()));
        String token = BridgePermanentJavaSources.unique(occupied, "$ironwood$token");
        String receiver = BridgePermanentJavaSources.unique(occupied, "$ironwood$receiver");
        String state = BridgePermanentJavaSources.unique(occupied, "$ironwood$state");
        String simple = type.sourceName().substring(type.sourceName().lastIndexOf('.') + 1);
        text.append(indent).append(annotation).append(indent).append("public ")
                .append(type.enclosingType().isPresent() ? "static " : "").append("enum ").append(simple).append(" {\n")
                .append(indent).append("    ").append(values.stream().map(value -> value.field().name() + "(" + value.token() + ")")
                        .collect(Collectors.joining(", "))).append(";\n")
                .append(indent).append("    private final int ").append(token).append(";\n")
                .append(indent).append("    private ").append(simple).append("(int token) { this.").append(token).append(" = token; }\n");
        for (var field : type.fields()) {
            if (values.stream().anyMatch(value -> value.field().name().equals(field.name()))) continue;
            text.append(indent).append("    public static final ").append(BridgePermanentJavaSources.javaType(field.type(), surface))
                    .append(' ').append(field.name()).append(" = ").append(BridgeJavaSources.literal(field.constant().orElseThrow())).append(";\n");
        }
        for (var method : type.callables()) {
            if (method.owner().equals("ironwood.lang.Object")) continue;
            if (method.synthetic() && method.isStatic() && method.owner().equals(type.binaryName())
                    && Set.of("values", "valueOf").contains(method.name())) continue;
            var dispatch = method.isStatic() ? null : BridgeEnumDispatch.prove(artifact, declared, method, constants);
            if (dispatch != null && dispatch.javaOnly()) continue;
            var formals = new ArrayList<String>();
            for (int index = 0; index < method.parameters().size(); index++) {
                formals.add(BridgePermanentJavaSources.javaType(method.parameters().get(index), surface) + " " + method.parameterNames().get(index));
            }
            String result = BridgePermanentJavaSources.javaType(method.result(), surface);
            var parameters = BridgeEnumArgumentSources.generate(text, artifact, admission, method, occupied, indent);
            String thrown = method.thrownTypes().isEmpty() ? "" : " throws " + method.thrownTypes().stream()
                    .map(value -> BridgePermanentJavaSources.javaType(value, surface)).collect(Collectors.joining(", "));
            String prefix = method.result().equals(IrType.VOID) ? "" : "return ";
            var natives = new LinkedHashMap<BridgeCallableId, String>();
            if (method.isStatic()) natives.put(method.target().orElseThrow(), BridgePermanentJavaSources.unique(occupied, "$ironwood$native$" + bindings.size()));
            else for (var target : dispatch.targets()) {
                if (!target.javaIdentity() && !natives.containsKey(target.callable())) natives.put(target.callable(),
                        BridgePermanentJavaSources.unique(occupied, "$ironwood$native$" + (bindings.size() + natives.size())));
            }
            String ownership = natives.keySet().stream().map(callable -> BridgeRootCalls.documentation(admission, callable))
                    .filter(value -> !value.isEmpty()).distinct().collect(Collectors.joining(" "));
            if (!ownership.isEmpty()) text.append(indent).append("    /** ").append(ownership).append(" */\n");
            text.append(indent).append("    public ").append(method.isStatic() ? "static " : "").append(result).append(' ').append(method.name())
                    .append('(').append(String.join(", ", formals)).append(')').append(thrown).append(" {\n");
            var arguments = new ArrayList<>(parameters.arguments());
            if (!method.isStatic()) arguments.addFirst("this." + token);
            if (method.isStatic()) {
                text.append(indent).append("        ").append(ensure).append("();\n")
                        .append(indent).append("        ").append(prefix).append(natives.values().iterator().next())
                        .append('(').append(arguments(admission, method.target().orElseThrow(), arguments, stateType)).append(");\n");
            } else {
                text.append(indent).append("        switch (this.").append(token).append(") {\n");
                for (var target : dispatch.targets()) {
                    text.append(indent).append("            case ").append(target.constant().token()).append(": ");
                    if (target.javaIdentity()) {
                        if (!method.name().equals("toString") || !method.parameters().isEmpty()) throw new IllegalArgumentException("unsupported mixed Java enum identity dispatch");
                        text.append("return name();\n");
                    } else {
                        text.append(ensure).append("(); ").append(prefix).append(natives.get(target.callable()))
                                .append('(').append(arguments(admission, target.callable(), arguments, stateType)).append(");")
                                .append(method.result().equals(IrType.VOID) ? " return;\n" : "\n");
                    }
                }
                text.append(indent).append("            default: throw new java.lang.AssertionError(\"invalid generated enum token\");\n")
                        .append(indent).append("        }\n");
            }
            text.append(indent).append("    }\n");
            var nativeFormals = new ArrayList<>(parameters.nativeFormals());
            if (!method.isStatic()) nativeFormals.addFirst("int " + receiver);
            String descriptor = "(" + (method.isStatic() ? "" : "I") + parameters.descriptor() + ")" + BridgeJavaTypes.descriptor(method.result());
            for (var nativeMethod : natives.entrySet()) {
                String entry = entries.get(nativeMethod.getKey());
                if (entry == null) throw new IllegalArgumentException("enum declaration lacks its proved exact entry");
                boolean reserve = BridgeRootCalls.reservation(admission, nativeMethod.getKey()).isPresent();
                var privateFormals = new ArrayList<>(nativeFormals);
                if (reserve) privateFormals.addFirst(stateType + " " + state);
                String privateDescriptor = reserve ? "(L" + stateType.replace('.', '/') + ";" + descriptor.substring(1) : descriptor;
                text.append(indent).append("    private static native ").append(result).append(' ').append(nativeMethod.getValue())
                        .append('(').append(String.join(", ", privateFormals)).append(')').append(thrown).append(";\n");
                bindings.add(new BridgeJavaSources.Binding(type.binaryName(), nativeMethod.getValue(), privateDescriptor, method, entry, "", parameters.tokens()));
            }
        }
        return new BridgePermanentJavaSources.EnumFacade(type.binaryName(), token, values);
    }

    private static String arguments(BridgeObjectAdmission admission, BridgeCallableId callable, List<String> arguments, String stateType) {
        if (BridgeRootCalls.reservation(admission, callable).isEmpty()) return String.join(", ", arguments);
        var prepared = new ArrayList<>(arguments); prepared.addFirst("new " + stateType + "()"); return String.join(", ", prepared);
    }
}
