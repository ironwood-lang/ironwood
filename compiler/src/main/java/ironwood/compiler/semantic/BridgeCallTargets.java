// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared resolved call/initialization edges for bridge proof consumers. */
public final class BridgeCallTargets {
    record Call(List<IrFunction> targets, List<IrOperand> arguments,
                Optional<IrValueReference> result, boolean complete) {
        Call {
            targets = List.copyOf(targets);
            arguments = List.copyOf(arguments);
        }
    }
    public record Initializers(List<IrFunction> targets, boolean complete) {
        public Initializers { targets = List.copyOf(targets); }
    }

    private final IrProgram program;
    private final Map<String, IrFunction> functions = new LinkedHashMap<>();

    public BridgeCallTargets(IrProgram program) {
        this.program = program;
        program.functions().forEach(function -> functions.put(function.linkageName(), function));
    }

    IrFunction function(String linkage) { return functions.get(linkage); }

    Call resolve(IrInstruction instruction) {
        if (instruction instanceof IrForeignCallInstruction call) {
            // The generated native adapter is not the Java implementation. Even
            // a matching native symbol cannot close this foreign effect edge.
            return new Call(List.of(), call.arguments(), call.result(), false);
        }
        if (instruction instanceof IrCallInstruction call) {
            IrFunction target = functions.get(call.targetLinkageName());
            return new Call(target == null ? List.of() : List.of(target), call.arguments(), call.result(), target != null);
        }
        if (instruction instanceof IrEnsureTypeInitializedInstruction ensure) {
            Initializers initialization = initializers(ensure.typeName());
            return new Call(initialization.targets(), List.of(), Optional.empty(), initialization.complete());
        }
        if (instruction instanceof IrVirtualCallInstruction call) {
            return dispatch(call.slot().index(), call.arguments(), call.result());
        }
        if (instruction instanceof IrInterfaceCallInstruction call) {
            return dispatch(call.slot().index(), call.arguments(), call.result());
        }
        return null;
    }

    private Call dispatch(int slot, List<IrOperand> arguments, Optional<IrValueReference> result) {
        var names = java.util.stream.Stream.concat(
                program.classes().stream().flatMap(type -> type.dispatchEntries().stream()),
                program.arrayTypes().stream().flatMap(type -> type.dispatchEntries().stream()))
                .filter(entry -> entry.slot().index() == slot).map(IrDispatchEntry::targetLinkageName).distinct().toList();
        return new Call(names.stream().filter(functions::containsKey).map(functions::get).toList(), arguments,
                result, !names.isEmpty() && names.stream().allMatch(functions::containsKey));
    }

    public Initializers initializers(String typeName) {
        Set<String> visited = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>(List.of(typeName));
        Set<IrFunction> result = new LinkedHashSet<>();
        boolean complete = true;
        for (int index = 0; index < pending.size(); index++) {
            String current = pending.get(index);
            if (!visited.add(current)) continue;
            boolean found = false;
            for (IrTypeInitialization type : program.typeInitializations()) {
                if (!type.typeName().equals(current)) continue;
                found = true;
                pending.addAll(type.prerequisiteTypes());
                if (type.initializerLinkageName().isPresent()) {
                    IrFunction initializer = functions.get(type.initializerLinkageName().orElseThrow());
                    if (initializer == null) complete = false;
                    else result.add(initializer);
                }
            }
            complete &= found;
        }
        return new Initializers(List.copyOf(result), complete);
    }

    /** Every possible native descriptor, without choosing only a favorable subtype. */
    List<IrClass> dynamicTypes(IrType type) {
        if (!type.isNominalReference()) return List.of();
        Optional<IrClass> declaration = program.classes().stream()
                .filter(candidate -> candidate.name().equals(type.referenceName())).findFirst();
        if (declaration.isEmpty()) return List.of();
        int typeId = declaration.orElseThrow().typeId();
        return program.classes().stream().filter(IrClass::isClass)
                .filter(candidate -> candidate.typeMembership().contains(typeId)).toList();
    }

    Call cleanup(IrOperand object, boolean rollback) {
        if (object.type().isArray()) return new Call(List.of(), List.of(object), Optional.empty(), true);
        List<IrClass> types = dynamicTypes(object.type().erasure());
        List<String> names = types.stream().flatMap(type -> (rollback ? type.constructorRollback()
                : type.destructorChain()).stream()).distinct().toList();
        return new Call(names.stream().filter(functions::containsKey).map(functions::get).toList(), List.of(object),
                Optional.empty(), !types.isEmpty() && names.stream().allMatch(functions::containsKey));
    }
}
