// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;

/** Permanent values within a mixed root surface, never permission to reclaim their storage. */
public final class BridgePermanentValues {
    private final BridgeRootSet entries;
    private final Optional<BridgeEnumLifetime> enums;
    private final BridgePermanentContract contract;

    private BridgePermanentValues(BridgeRootSet entries, Optional<BridgeEnumLifetime> enums, BridgePermanentContract contract) {
        this.entries = entries;
        this.enums = enums;
        this.contract = contract;
    }

    public BridgeRootSet entries() { return entries; }
    public Optional<BridgeEnumLifetime> enums() { return enums; }
    public BridgePermanentContract contract() { return contract; }
    public boolean matches(IrProgram program, BridgeRootSet requested) {
        return contract.program().equals(program) && entries.equals(requested.revalidate(program))
                && enums.map(value -> value.matches(program, requested)).orElse(true);
    }

    public static BridgePermanentValues prove(CompilationArtifact artifact, BridgeRootSet requested, Set<IrType> candidates) {
        return prove(artifact, requested, candidates, Optional.empty());
    }

    public static BridgePermanentValues prove(CompilationArtifact artifact, BridgeRootSet requested, Set<IrType> candidates,
            BridgeEnumConversions conversions) {
        return prove(artifact, requested, candidates, Optional.of(conversions));
    }

    private static BridgePermanentValues prove(CompilationArtifact artifact, BridgeRootSet requested, Set<IrType> candidates,
            Optional<BridgeEnumConversions> conversions) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty() || artifact.bridgeApiFacts().isEmpty()
                || candidates.isEmpty()) throw new IllegalArgumentException("mixed permanent values require final facts and explicit candidates");
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var api = artifact.bridgeApiFacts().orElseThrow();
        var entries = requested.revalidate(program);
        if (!facts.matches(program) || !api.matches(program) || !entries.resolved()
                || conversions.isPresent() && !conversions.orElseThrow().matches(program, entries)) {
            throw new IllegalArgumentException("mixed permanent facts do not match the complete program and entries");
        }
        var enums = conversions.map(value -> BridgeEnumLifetime.prove(artifact, value));
        var roots = enums.map(value -> value.contract().roots()).orElse(entries);
        var references = new LinkedHashMap<IrType, BridgeNonReclamationContract>();
        enums.ifPresent(value -> references.putAll(value.contract().references()));
        var domain = BridgeGenericDomain.forRoots(artifact, entries);
        for (var type : candidates.stream().sorted(java.util.Comparator.comparing(IrType::displayName)).toList()) {
            var declaration = type.isNominalReference() ? api.types().get(type.referenceName()) : null;
            boolean variable = type.isTypeParameter() && domain.contains(type);
            boolean alternative = domain.variables().values().stream().anyMatch(types -> types.contains(type));
            if (!variable && (declaration == null || declaration.kind() != BridgeApiFacts.Kind.CLASS || !declaration.finalType()
                    || declaration.abstractType() || declaration.throwable() || declaration.generic()
                        && !(domain.contains(type) || type.equals(declaration.exactType()) && domain.applications().containsKey(declaration.binaryName()))
                    || type.equals(IrType.reference("ironwood.lang.String")) || entries.roots().stream().noneMatch(root ->
                    root.callable().parameters().contains(type) || root.callable().result().equals(type)) && !alternative)) {
                throw new IllegalArgumentException("permanent value candidate requires an exposed exact concrete object type: " + type.displayName());
            }
            var proof = BridgeNonReclamationAnalyzer.analyze(program, roots, type, facts);
            if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
            references.put(type, proof.contract().orElseThrow());
            if (!type.typeArguments().isEmpty()) {
                var storage = BridgeGenericDomain.storage(type);
                var family = BridgeNonReclamationAnalyzer.analyze(program, roots, storage, facts);
                if (family.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(family.reason());
                references.put(storage, family.contract().orElseThrow());
            }
        }
        return new BridgePermanentValues(entries, enums, new BridgePermanentContract(program, roots, references, java.util.Map.of()));
    }
}
