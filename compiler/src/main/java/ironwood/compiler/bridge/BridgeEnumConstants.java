// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeApiFacts;
import ironwood.compiler.semantic.BridgeCallTargets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact named conversion metadata; initialization and invocation effects remain proof obligations. */
public final class BridgeEnumConstants {
    public record Constant(int token, IrStaticField field) {}

    private final IrProgram program;
    private final Map<IrType, List<Constant>> constants;
    private final List<BridgeCallableId> initializers;

    private BridgeEnumConstants(IrProgram program, Map<IrType, List<Constant>> constants,
            List<BridgeCallableId> initializers) {
        this.program = program;
        this.constants = Map.copyOf(constants);
        this.initializers = List.copyOf(initializers);
    }

    public Map<IrType, List<Constant>> constants() { return constants; }
    public List<BridgeCallableId> initializers() { return initializers; }
    public boolean matches(IrProgram candidate, Set<IrType> types) {
        return program.equals(candidate) && constants.keySet().equals(types);
    }

    /** Stable within the paired artifact, independent of native declaration ordinals. */
    public static BridgeEnumConstants discover(CompilationArtifact artifact, Set<IrType> types) {
        var facts = facts(artifact);
        Map<IrType, Map<String, Integer>> tokens = new LinkedHashMap<>();
        for (var type : types) {
            var declaration = declaration(facts, type);
            Map<String, Integer> named = new LinkedHashMap<>();
            declaration.enumConstants().stream().map(BridgeApiFacts.EnumConstant::name).sorted()
                    .forEach(name -> named.put(name, named.size()));
            tokens.put(type, named);
        }
        return prove(artifact, tokens);
    }

    /** Validates every declared name, resolved storage type and producer-selected token. */
    public static BridgeEnumConstants prove(CompilationArtifact artifact, Map<IrType, Map<String, Integer>> tokens) {
        var facts = facts(artifact);
        var program = artifact.program().orElseThrow();
        var targets = new BridgeCallTargets(program);
        Map<IrType, List<Constant>> mappings = new LinkedHashMap<>();
        List<BridgeCallableId> initializers = new ArrayList<>();
        for (var type : tokens.keySet().stream().sorted(java.util.Comparator.comparing(IrType::displayName)).toList()) {
            var declaration = declaration(facts, type);
            var named = tokens.get(type);
            var expected = declaration.enumConstants().stream().map(BridgeApiFacts.EnumConstant::name)
                    .collect(java.util.stream.Collectors.toSet());
            if (named == null || !named.keySet().equals(expected)
                    || named.values().stream().anyMatch(token -> token == null || token < 0)
                    || named.values().stream().distinct().count() != named.size()) {
                throw new IllegalArgumentException("incomplete or invalid named enum tokens for " + type.displayName());
            }
            List<Constant> constants = new ArrayList<>();
            for (var constant : declaration.enumConstants()) {
                var fields = program.staticFields().stream().filter(field -> field.ownerClass().equals(type.referenceName())
                        && field.name().equals(constant.name()) && field.type().equals(type) && field.isFinal()
                        && field.initialValue() instanceof IrEnumConstant value && value.type().equals(type)
                        && value.constantName().equals(constant.name()) && value.storageType().equals(constant.nativeType())).toList();
                if (fields.size() != 1) throw new IllegalArgumentException("named enum field disagrees with final semantic facts: "
                        + type.displayName() + "." + constant.name());
                constants.add(new Constant(named.get(constant.name()), fields.getFirst()));
            }
            mappings.put(type, List.copyOf(constants));
            var initialization = targets.initializers(type.referenceName());
            if (!initialization.complete()) throw new IllegalArgumentException("incomplete enum initialization: " + type.displayName());
            initialization.targets().stream().map(BridgeCallableId::of).forEach(initializers::add);
        }
        return new BridgeEnumConstants(program, mappings, initializers.stream().distinct()
                .sorted(java.util.Comparator.comparing(BridgeCallableId::linkage)).toList());
    }

    private static BridgeApiFacts facts(CompilationArtifact artifact) {
        if (!artifact.valid() || artifact.bridgeApiFacts().isEmpty()
                || !artifact.bridgeApiFacts().orElseThrow().matches(artifact.program().orElseThrow())) {
            throw new IllegalArgumentException("enum constants require matching final semantic API facts");
        }
        return artifact.bridgeApiFacts().orElseThrow();
    }

    private static BridgeApiFacts.Type declaration(BridgeApiFacts facts, IrType type) {
        var declaration = type.isNominalReference() && type.typeArguments().isEmpty()
                ? facts.types().get(type.referenceName()) : null;
        if (declaration == null || declaration.kind() != BridgeApiFacts.Kind.ENUM) {
            throw new IllegalArgumentException("named enum metadata requires a resolved enum type: " + type.displayName());
        }
        return declaration;
    }
}
