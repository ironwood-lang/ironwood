// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finite native production domain, never inferred from an unchecked Java client view. */
public record BridgeGenericDomain(Map<String, List<IrType>> applications,
                                  Map<String, List<IrType>> variables, Set<IrType> inputs) {
    public BridgeGenericDomain {
        applications = immutable(applications);
        variables = immutable(variables);
        inputs = Set.copyOf(inputs);
    }

    public BridgeGenericDomain(Map<String, List<IrType>> applications, Map<String, List<IrType>> variables) {
        this(applications, variables, Set.of());
    }

    private static Map<String, List<IrType>> immutable(Map<String, List<IrType>> source) {
        var result = new java.util.TreeMap<String, List<IrType>>();
        source.forEach((name, types) -> result.put(name, List.copyOf(types)));
        return java.util.Collections.unmodifiableMap(result);
    }

    public static BridgeGenericDomain discover(CompilationArtifact artifact, List<BridgeApiFacts.Type> selected) {
        var facts = artifact.bridgeApiFacts().orElseThrow();
        if (!facts.matches(artifact.program().orElseThrow())) throw new IllegalArgumentException("generic domain requires final source facts");
        var declarations = new LinkedHashMap<String, BridgeApiFacts.Type>();
        selected.stream().filter(BridgeApiFacts.Type::generic).forEach(type -> declarations.put(type.binaryName(), type));
        var applications = new LinkedHashMap<String, Set<IrType>>();
        var inputs = new LinkedHashSet<IrType>();
        declarations.keySet().forEach(name -> applications.put(name, new LinkedHashSet<>()));
        for (var type : declarations.values()) {
            if (!type.finalType() || type.kind() != BridgeApiFacts.Kind.CLASS || type.throwable()
                    || type.enclosingType().isPresent()
                    || !type.supertypes().equals(List.of(IrType.reference("ironwood.lang.Object")))) {
                throw new IllegalArgumentException("Java Bridge requires a top-level final generic class without generic inheritance: " + type.sourceName());
            }
            for (var variable : type.typeParameters()) {
                for (var bound : variable.upperBounds()) {
                    if (bound.equals(IrType.reference("ironwood.lang.Object"))) continue;
                    if (bound.isTypeParameter() && type.typeParameters().stream().anyMatch(other -> other.id().equals(bound.referenceName()))) continue;
                    if (!finalFacade(bound, selected)) {
                        throw new IllegalArgumentException("generic bound requires an exported final reference facade: " + bound.displayName());
                    }
                }
            }
            boolean bounded = bounded(type, selected);
            for (var method : type.callables()) {
                if (method.generic()) {
                    throw new IllegalArgumentException("public generic Java Bridge methods remain unsupported: " + type.sourceName() + "." + method.name());
                }
                if (!bounded && (method.kind() == IrCallableKind.CONSTRUCTOR
                        || method.parameters().stream().anyMatch(BridgeGenericDomain::dependent))) {
                    throw new IllegalArgumentException("generic constructors and dependent inputs require one final facade bound per variable: "
                            + type.sourceName() + "." + method.name());
                }
            }
            if (bounded) {
                // A final bound admits exactly one native argument, independently
                // of observed allocations, including construction from Java.
                applications.get(type.binaryName()).add(IrType.reference(type.binaryName(), type.typeParameters().stream()
                        .map(variable -> variable.upperBounds().getFirst()).toList()));
                type.typeParameters().forEach(variable -> inputs.add(IrType.typeParameter(variable.id(), variable.upperBounds().getFirst())));
                inputs.add(type.exactType());
            }
        }
        // All native allocations are considered, including private production.
        // An unresolved allocation cannot acquire a finite domain from its erasure.
        for (var function : artifact.program().orElseThrow().functions()) {
            for (var block : function.blocks()) {
                var instructions = new ArrayList<>(block.instructions());
                if (block.terminator() instanceof IrInvokeTerminator invoke) instructions.add(invoke.call());
                for (var instruction : instructions) {
                    if (instruction instanceof IrAllocateInstruction allocation && declarations.containsKey(allocation.className())) {
                        add(allocation.result().type(), declarations, selected, applications);
                    }
                }
            }
        }
        for (var type : selected) {
            for (var method : type.callables()) {
                for (var parameter : method.parameters()) {
                    if (!containsApplication(parameter, declarations.keySet())) continue;
                    var declaration = parameter.isNominalReference() ? declarations.get(parameter.referenceName()) : null;
                    if (declaration == null || !bounded(declaration, selected)) {
                        throw new IllegalArgumentException("generic facade inputs require final facade bounds: " + type.sourceName() + "." + method.name());
                    }
                    add(parameter, declarations, selected, applications);
                    inputs.add(parameter);
                }
                if (method.result().isNominalReference() && declarations.containsKey(method.result().referenceName())) {
                    if (!dependent(method.result())) add(method.result(), declarations, selected, applications);
                    else if (!type.generic() || !method.result().equals(type.exactType())) {
                        throw new IllegalArgumentException("generic result has no exact finite native view: " + method.name());
                    }
                }
            }
        }
        var variables = new LinkedHashMap<String, List<IrType>>();
        var complete = new LinkedHashMap<String, List<IrType>>();
        for (var entry : applications.entrySet()) {
            if (entry.getValue().isEmpty()) throw new IllegalArgumentException("generic facade has no concrete production domain: " + entry.getKey());
            var types = entry.getValue().stream().sorted(java.util.Comparator.comparing(IrType::displayName)).toList();
            complete.put(entry.getKey(), types);
            var declaration = declarations.get(entry.getKey());
            for (int i = 0; i < declaration.typeParameters().size(); i++) {
                int index = i;
                variables.put(declaration.typeParameters().get(i).id(), types.stream()
                        .map(type -> type.typeArguments().get(index)).distinct().toList());
            }
        }
        return new BridgeGenericDomain(complete, variables, inputs);
    }

    private static void add(IrType application, Map<String, BridgeApiFacts.Type> declarations,
            List<BridgeApiFacts.Type> selected, Map<String, Set<IrType>> applications) {
        var declaration = declarations.get(application.referenceName());
        if (!application.isNominalReference() || application.typeArguments().size() != declaration.typeParameters().size()) {
            throw new IllegalArgumentException("generic application has no complete native arguments: " + application.displayName());
        }
        var arguments = application.typeArguments().stream().map(argument ->
                bounded(declaration, selected) && argument.isTypeParameter() ? argument.erasure() : argument).toList();
        for (var argument : arguments) {
            if (!finalFacade(argument, selected)) {
                throw new IllegalArgumentException("generic application requires an exported final reference facade: " + application.displayName());
            }
        }
        if (bounded(declaration, selected) && !arguments.equals(declaration.typeParameters().stream()
                .map(variable -> variable.upperBounds().getFirst()).toList())) {
            throw new IllegalArgumentException("generic application differs from its final bounds: " + application.displayName());
        }
        applications.get(application.referenceName()).add(IrType.reference(application.referenceName(), arguments));
    }

    private static boolean bounded(BridgeApiFacts.Type type, List<BridgeApiFacts.Type> selected) {
        return !type.typeParameters().isEmpty() && type.typeParameters().stream().allMatch(variable ->
                variable.upperBounds().size() == 1 && finalFacade(variable.upperBounds().getFirst(), selected));
    }

    private static boolean finalFacade(IrType argument, List<BridgeApiFacts.Type> selected) {
        if (!argument.isNominalReference() || !argument.typeArguments().isEmpty()) return false;
        return selected.stream().anyMatch(type -> type.binaryName().equals(argument.referenceName())
                && !type.generic() && type.kind() == BridgeApiFacts.Kind.CLASS && type.finalType()
                && !type.abstractType() && !type.throwable());
    }

    public boolean contains(IrType type) {
        if (type.isTypeParameter()) return variables.containsKey(type.referenceName());
        if (!type.isNominalReference()) return false;
        return applications.getOrDefault(type.referenceName(), List.of()).contains(type);
    }

    /** Native storage identity only; surface admission must prove final variable bounds before inputs are allowed. */
    public static IrType storage(IrType type) {
        return type.isNominalReference() || type.isTypeParameter() ? type.erasure() : type;
    }

    public static BridgeGenericDomain forRoots(CompilationArtifact artifact, BridgeRootSet roots) {
        var names = new LinkedHashSet<String>();
        for (var root : roots.roots()) {
            names.add(root.callable().owner());
            root.callable().parameters().forEach(type -> names(type, names));
            names(root.callable().result(), names);
        }
        return discover(artifact, artifact.bridgeApiFacts().orElseThrow().types().values().stream()
                .filter(type -> names.contains(type.binaryName())).toList());
    }

    private static void names(IrType type, Set<String> names) {
        if (type.isTypeParameter()) names(type.erasure(), names);
        if (type.isNominalReference()) names.add(type.referenceName());
        type.typeArguments().forEach(argument -> names(argument, names));
    }

    public static boolean dependent(IrType type) {
        return type.isTypeParameter() || type.isWildcard()
                || type.isArray() && dependent(type.elementType())
                || type.typeArguments().stream().anyMatch(BridgeGenericDomain::dependent);
    }

    private static boolean containsApplication(IrType type, Set<String> names) {
        return type.isNominalReference() && names.contains(type.referenceName())
                || type.isArray() && containsApplication(type.elementType(), names)
                || type.typeArguments().stream().anyMatch(argument -> containsApplication(argument, names));
    }
}
