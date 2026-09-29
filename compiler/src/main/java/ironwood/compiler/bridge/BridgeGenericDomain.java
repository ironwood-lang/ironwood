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
                                  Map<String, List<IrType>> variables) {
    public BridgeGenericDomain {
        applications = immutable(applications);
        variables = immutable(variables);
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
        declarations.keySet().forEach(name -> applications.put(name, new LinkedHashSet<>()));
        for (var type : declarations.values()) {
            if (!type.finalType() || type.kind() != BridgeApiFacts.Kind.CLASS || type.throwable()
                    || type.enclosingType().isPresent()
                    || !type.supertypes().equals(List.of(IrType.reference("ironwood.lang.Object")))) {
                throw new IllegalArgumentException("P7d1 requires a top-level final generic class without generic inheritance: " + type.sourceName());
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
            for (var method : type.callables()) {
                if (method.kind() == IrCallableKind.CONSTRUCTOR || method.generic()) {
                    throw new IllegalArgumentException("P7d1 generic facades require inaccessible constructors and no public generic methods: " + type.sourceName());
                }
                if (method.parameters().stream().anyMatch(BridgeGenericDomain::dependent)) {
                    throw new IllegalArgumentException("P7d1 does not admit type-dependent inputs: " + type.sourceName() + "." + method.name());
                }
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
                if (method.parameters().stream().anyMatch(parameter -> containsApplication(parameter, declarations.keySet()))) {
                    throw new IllegalArgumentException("P7d1 generic facade inputs require a later input contract: " + type.sourceName() + "." + method.name());
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
        return new BridgeGenericDomain(complete, variables);
    }

    private static void add(IrType application, Map<String, BridgeApiFacts.Type> declarations,
            List<BridgeApiFacts.Type> selected, Map<String, Set<IrType>> applications) {
        var declaration = declarations.get(application.referenceName());
        if (!application.isNominalReference() || application.typeArguments().size() != declaration.typeParameters().size()) {
            throw new IllegalArgumentException("generic application has no complete native arguments: " + application.displayName());
        }
        for (var argument : application.typeArguments()) {
            if (!finalFacade(argument, selected)) {
                throw new IllegalArgumentException("generic application requires an exported final reference facade: " + application.displayName());
            }
        }
        applications.get(application.referenceName()).add(application);
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

    /** Native reference applications share one allocation and destruction identity. */
    public static IrType storage(IrType type) {
        return type.isNominalReference() ? type.erasure() : type;
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
