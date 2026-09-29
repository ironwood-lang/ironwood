// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.bridge.BridgeGenericDomain;
import ironwood.compiler.ir.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Conservative closed-world domains for read-only reference factory products. */
final class BridgeGenericAllocations {
    private BridgeGenericAllocations() {}

    static Map<String, Set<IrType>> project(IrProgram program, Map<String, TypeSymbol> symbols) {
        var result = new LinkedHashMap<String, Set<IrType>>();
        for (var owner : symbols.values()) {
            if (owner.typeParameters().isEmpty() || !owner.isFinal() || owner.isEnum()
                    || owner.constructors().stream().anyMatch(method -> method.accessModifier() == AccessModifier.PUBLIC)
                    || owner.declaredMethods().values().stream().anyMatch(method -> method.accessModifier() == AccessModifier.PUBLIC
                        && (!method.typeVariables().isEmpty() || method.parameterTypes().stream().anyMatch(BridgeGenericDomain::dependent)))
                    || owner.declaredFields().values().stream().anyMatch(field -> field.accessModifier() == AccessModifier.PUBLIC
                        && !field.isFinal())) continue;
            var domains = new LinkedHashMap<String, Set<IrType>>();
            owner.typeParameters().forEach(variable -> domains.put(variable.id(), new LinkedHashSet<>()));
            boolean complete = true;
            for (var function : program.functions()) {
                for (var block : function.blocks()) {
                    var instructions = new java.util.ArrayList<>(block.instructions());
                    if (block.terminator() instanceof IrInvokeTerminator invoke) instructions.add(invoke.call());
                    for (var instruction : instructions) {
                        if (!(instruction instanceof IrAllocateInstruction allocation) || !allocation.className().equals(owner.name())) continue;
                        var arguments = allocation.result().type().typeArguments();
                        if (arguments.size() != owner.typeParameters().size()) { complete = false; continue; }
                        for (int i = 0; i < arguments.size(); i++) {
                            var type = arguments.get(i);
                            var symbol = type.isNominalReference() ? symbols.get(type.referenceName()) : null;
                            if (symbol == null || !symbol.isFinal() || !type.typeArguments().isEmpty()) complete = false;
                            else domains.get(owner.typeParameters().get(i).id()).add(type);
                        }
                    }
                }
            }
            if (complete && domains.values().stream().noneMatch(Set::isEmpty)) {
                domains.forEach((name, types) -> result.put(name, Set.copyOf(types)));
            }
        }
        return Map.copyOf(result);
    }
}
