// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeApiFacts;
import ironwood.compiler.semantic.BridgeCallTargets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bound enum conversion inventory for mixed surfaces; grants no lifetime or invocation permission. */
public final class BridgeEnumConversions {
    public record Parameter(int input, IrType declaredType, List<BridgeEnumConstants.Constant> constants, boolean nullable) {
        public Parameter { constants = List.copyOf(constants); }
    }

    public record Result(IrType declaredType, List<BridgeEnumConstants.Constant> constants) {
        public Result { constants = List.copyOf(constants); }
    }

    private final IrProgram program;
    private final BridgeRootSet entries;
    private final Map<BridgeCallableId, List<Parameter>> parameters;
    private final Map<BridgeCallableId, Result> results;
    private final Set<IrType> enumTypes;
    private final List<BridgeCallableId> initializers;

    private BridgeEnumConversions(IrProgram program, BridgeRootSet entries,
            Map<BridgeCallableId, List<Parameter>> parameters, Map<BridgeCallableId, Result> results,
            Set<IrType> enumTypes, List<BridgeCallableId> initializers) {
        this.program = program;
        this.entries = entries;
        this.parameters = Map.copyOf(parameters);
        this.results = Map.copyOf(results);
        this.enumTypes = Set.copyOf(enumTypes);
        this.initializers = List.copyOf(initializers);
    }

    public Map<BridgeCallableId, List<Parameter>> parameters() { return parameters; }
    public Map<BridgeCallableId, Result> results() { return results; }
    public Set<IrType> enumTypes() { return enumTypes; }
    public List<BridgeCallableId> initializers() { return initializers; }
    public boolean matches(IrProgram candidate, BridgeRootSet roots) {
        return program.equals(candidate) && entries.equals(roots.revalidate(candidate));
    }

    public static BridgeEnumConversions forSurface(CompilationArtifact artifact, BridgeExportSurface surface) {
        var selected = BridgeExportSurface.objectValues(artifact,
                surface.types().stream().map(BridgeApiFacts.Type::packageName).distinct().sorted().toList());
        if (selected.surface().isEmpty() || !selected.surface().orElseThrow().equals(surface)) {
            throw new IllegalArgumentException("enum conversion requires the complete current object/value signature surface");
        }
        var types = surface.types().stream().filter(type -> type.kind() == BridgeApiFacts.Kind.ENUM).toList();
        var constants = BridgeEnumConstants.discover(artifact, types.stream().map(type -> IrType.reference(type.binaryName()))
                .collect(java.util.stream.Collectors.toSet()));
        List<BridgeEnumDispatch> dispatches = new ArrayList<>();
        for (var type : types) for (var method : type.callables()) {
            if (!method.isStatic() && method.kind() == IrCallableKind.METHOD && !method.owner().equals("ironwood.lang.Object")) {
                dispatches.add(BridgeEnumDispatch.prove(artifact, IrType.reference(type.binaryName()), method, constants));
            }
        }
        return prove(artifact, surface.roots(), constants, dispatches);
    }

    public static BridgeEnumConversions prove(CompilationArtifact artifact, BridgeRootSet requested,
            BridgeEnumConstants constants, List<BridgeEnumDispatch> dispatches) {
        if (!artifact.valid() || artifact.bridgeApiFacts().isEmpty() || artifact.bridgeConstructionFacts().isEmpty()) {
            throw new IllegalArgumentException("enum conversion requires final semantic facts");
        }
        var program = artifact.program().orElseThrow();
        var api = artifact.bridgeApiFacts().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var entries = requested.revalidate(program);
        if (!api.matches(program) || !facts.matches(program) || !entries.problems().isEmpty()
                || !constants.matches(program, constants.constants().keySet())) {
            throw new IllegalArgumentException("enum conversion facts do not match the program and entries");
        }
        var ids = entries.roots().stream().map(BridgeRootSet.Root::callable).collect(java.util.stream.Collectors.toSet());
        Map<BridgeCallableId, Parameter> receivers = new LinkedHashMap<>();
        for (var dispatch : dispatches) {
            if (!dispatch.matches(program, dispatch.type(), dispatch.method()) || !constants.constants().containsKey(dispatch.type())) {
                throw new IllegalArgumentException("enum conversion has stale dispatch metadata");
            }
            for (var target : dispatch.targets()) {
                if (!constants.constants().get(dispatch.type()).contains(target.constant())) {
                    throw new IllegalArgumentException("enum conversion token pairing differs from dispatch metadata");
                }
                if (target.javaIdentity()) continue;
                if (!ids.contains(target.callable())) throw new IllegalArgumentException("enum conversion is missing a resolved target entry");
                var prior = receivers.get(target.callable());
                if (prior != null && !prior.declaredType().equals(dispatch.type())) {
                    throw new IllegalArgumentException("enum body is shared by incompatible receiver types");
                }
                var allowed = new ArrayList<>(prior == null ? List.of() : prior.constants());
                if (!allowed.contains(target.constant())) allowed.add(target.constant());
                receivers.put(target.callable(), new Parameter(0, dispatch.type(), allowed, false));
            }
        }
        Map<BridgeCallableId, List<Parameter>> parameters = new LinkedHashMap<>();
        Map<BridgeCallableId, Result> results = new LinkedHashMap<>();
        Set<IrType> used = new LinkedHashSet<>();
        for (var root : entries.roots()) {
            var id = root.callable();
            var resultEnum = enumDeclaration(api, id.result());
            if (resultEnum != null) {
                if (!resultEnum.equals(id.result()) || !constants.constants().containsKey(resultEnum)) {
                    throw new IllegalArgumentException("enum result has no declared named conversion");
                }
                results.put(id, new Result(resultEnum, constants.constants().get(resultEnum)));
                used.add(resultEnum);
            }
            List<Parameter> converted = new ArrayList<>();
            for (int input = 0; input < id.parameters().size(); input++) {
                var declared = enumDeclaration(api, id.parameters().get(input));
                if (declared == null) continue;
                if (!constants.constants().containsKey(declared)) throw new IllegalArgumentException("enum input has no named conversion");
                if (input == 0 && !facts.isStatic(id)) {
                    var receiver = receivers.get(id);
                    if (receiver == null) throw new IllegalArgumentException("enum receiver has no exact dispatch conversion");
                    converted.add(receiver);
                } else {
                    if (!declared.equals(id.parameters().get(input))) throw new IllegalArgumentException("enum argument requires its declared enum type");
                    converted.add(new Parameter(input, declared, constants.constants().get(declared), true));
                }
                used.add(declared);
            }
            parameters.put(id, List.copyOf(converted));
        }
        var targets = new BridgeCallTargets(program);
        List<BridgeCallableId> initializers = new ArrayList<>();
        for (var type : used) {
            var initialization = targets.initializers(type.referenceName());
            if (!initialization.complete()) throw new IllegalArgumentException("incomplete enum conversion initialization");
            initialization.targets().stream().map(BridgeCallableId::of).forEach(initializers::add);
        }
        return new BridgeEnumConversions(program, entries, parameters, results, used, initializers.stream().distinct()
                .sorted(java.util.Comparator.comparing(BridgeCallableId::linkage)).toList());
    }

    private static IrType enumDeclaration(BridgeApiFacts facts, IrType type) {
        var declaration = type.isNominalReference() ? facts.types().get(type.referenceName()) : null;
        if (declaration == null) return null;
        if (declaration.kind() == BridgeApiFacts.Kind.ENUM) return type;
        for (var parent : declaration.supertypes()) {
            var owner = parent.isNominalReference() ? facts.types().get(parent.referenceName()) : null;
            if (owner != null && owner.kind() == BridgeApiFacts.Kind.ENUM) return parent;
        }
        return null;
    }
}
