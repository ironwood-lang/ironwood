// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Private constant-entry selection without changing the exported Java API. */
final class BridgeFixedEnumSources {
    private BridgeFixedEnumSources() {}

    record Alternative(String condition, String invocation) {}

    static List<Alternative> generate(StringBuilder text, BridgeObjectAdmission admission,
            BridgeApiFacts.Callable method, String binaryName, String address, String receiver,
            String nativeResult, String conversion, String throwsClause, Set<String> occupied,
            List<BridgeJavaSources.Binding> bindings, BridgeCriticalSources.Emitter transport, String indent) {
        var alternatives = new ArrayList<Alternative>();
        var callable = method.target().orElseThrow();
        for (var entry : admission.entries().entries()) {
            if (!entry.root().callable().equals(callable) || entry.fixedEnums().isEmpty()) continue;
            if (entry.fixedEnums().size() != 1 || method.isStatic() || admission.roots().isPresent()) {
                throw new IllegalArgumentException("fixed enum Java selection requires an exact permanent instance entry");
            }
            int input = entry.fixedEnums().keySet().iterator().next();
            int token = entry.fixedEnums().get(input);
            var mapping = admission.entries().enumConversions().orElseThrow().parameters().get(callable).stream()
                    .filter(parameter -> parameter.input() == input).findFirst().orElseThrow();
            var constant = mapping.constants().stream().filter(value -> value.token() == token).findFirst().orElseThrow();
            String name = BridgePermanentJavaSources.unique(occupied, "$ironwood$fixedEnum");
            var formals = new ArrayList<String>();
            var arguments = new ArrayList<String>();
            formals.add("long " + receiver); arguments.add("this." + address);
            var descriptor = new StringBuilder("(J");
            for (int index = 0; index < method.parameters().size(); index++) {
                if (index + 1 == input) continue;
                var type = method.parameters().get(index);
                formals.add(BridgePermanentJavaSources.javaType(type, admission.surface()) + " " + method.parameterNames().get(index));
                arguments.add(method.parameterNames().get(index));
                descriptor.append(BridgeJavaTypes.descriptor(type));
            }
            descriptor.append(')').append(conversion.isEmpty() ? BridgeJavaTypes.descriptor(method.result()) : "J");
            text.append(indent).append("    private static native ").append(nativeResult).append(' ').append(name)
                    .append('(').append(String.join(", ", formals)).append(')').append(throwsClause).append(";\n");
            var reserved = transport != null && transport.admits(entry.function().linkageName()) ? transport.reserve() : null;
            if (reserved != null) transport.emit(text, reserved, name, formals, nativeResult,
                    BridgeCriticalSources.Carrier.of(!conversion.isEmpty(), method.result()), throwsClause, indent);
            bindings.add(new BridgeJavaSources.Binding(binaryName, name, descriptor.toString(), method,
                    entry.function().linkageName(), conversion, Set.of(), entry.fixedEnums(), reserved == null ? -1 : reserved.index()));
            String argument = method.parameterNames().get(input - 1);
            // Null must not trigger Java enum initialization through a constant field read.
            String condition = argument + " != null && " + argument + " == "
                    + BridgePermanentJavaSources.javaType(mapping.declaredType(), admission.surface()) + "." + constant.field().name();
            alternatives.add(new Alternative(condition, (reserved == null ? name : reserved.call()) + "(" + String.join(", ", arguments) + ")"));
        }
        return List.copyOf(alternatives);
    }

    static String select(List<Alternative> alternatives, String fallback) {
        var expression = new StringBuilder();
        for (var alternative : alternatives) expression.append(alternative.condition()).append(" ? ")
                .append(alternative.invocation()).append(" : ");
        return expression.append(fallback).toString();
    }

    static void emitVoidBranches(StringBuilder text, List<Alternative> alternatives, String indent) {
        for (var alternative : alternatives) text.append(indent).append("        if (").append(alternative.condition())
                .append(") { ").append(alternative.invocation()).append("; return; }\n");
    }
}
