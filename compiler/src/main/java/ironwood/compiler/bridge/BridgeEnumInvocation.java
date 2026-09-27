// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Enum-only invocation proofs, including exact receiver alternatives and copied String values. */
public final class BridgeEnumInvocation {
    private static final IrType STRING = IrType.reference("ironwood.lang.String");
    private final BridgeRootSet entries;
    private final BridgePermanentContract lifetime;
    private final BridgeEnumConversions conversions;
    private final Map<BridgeCallableId, BridgeStringResultContract> stringResults;

    private BridgeEnumInvocation(BridgeRootSet entries, BridgePermanentContract lifetime,
            BridgeEnumConversions conversions, Map<BridgeCallableId, BridgeStringResultContract> stringResults) {
        this.entries = entries;
        this.lifetime = lifetime;
        this.conversions = conversions;
        this.stringResults = Map.copyOf(stringResults);
    }

    public BridgeRootSet entries() { return entries; }
    public BridgePermanentContract lifetime() { return lifetime; }
    public BridgeEnumConversions conversions() { return conversions; }
    public Map<BridgeCallableId, List<BridgeEnumConversions.Parameter>> parameters() { return conversions.parameters(); }
    public Map<BridgeCallableId, BridgeStringResultContract> stringResults() { return stringResults; }
    public Map<BridgeCallableId, BridgeEnumConversions.Result> enumResults() { return conversions.results(); }

    public boolean matches(IrProgram program, BridgeRootSet roots) {
        return lifetime.program().equals(program) && entries.equals(roots.revalidate(program));
    }

    public static BridgeEnumInvocation prove(CompilationArtifact artifact, BridgeEnumConstants constants,
            List<BridgeEnumDispatch> dispatches, List<BridgeCallableId> staticEntries) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()) {
            throw new IllegalArgumentException("enum invocation requires final construction facts");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        if (!facts.matches(program) || !constants.matches(program, constants.constants().keySet())) {
            throw new IllegalArgumentException("enum invocation facts do not match the program");
        }
        var requested = new LinkedHashSet<BridgeCallableId>();
        dispatches.stream().flatMap(dispatch -> dispatch.targets().stream()).filter(target -> !target.javaIdentity())
                .map(BridgeEnumDispatch.Target::callable).forEach(requested::add);
        for (var id : staticEntries) {
            if (!facts.isStatic(id) || id.kind() != IrCallableKind.METHOD) {
                throw new IllegalArgumentException("enum static entry requires an exact static method");
            }
            requested.add(id);
        }
        var entries = BridgeRootSet.resolve(program, List.copyOf(requested));
        if (!entries.resolved()) throw new IllegalArgumentException("enum invocation requires resolved native bodies");
        var conversions = BridgeEnumConversions.prove(artifact, entries, constants, dispatches);
        Set<IrType> references = new LinkedHashSet<>();
        for (var root : entries.roots()) {
            var id = root.callable();
            if (id.result().isReference() && !id.result().equals(STRING)) {
                if (!conversions.results().containsKey(id)) throw new IllegalArgumentException(
                        "enum invocation reference result has no named conversion: " + id.result().displayName());
                references.add(id.result());
            }
            for (int input = 0; input < id.parameters().size(); input++) {
                var type = id.parameters().get(input);
                if (!type.isReference()) continue;
                if (type.equals(STRING)) {
                    boolean confined = id.result().equals(STRING) ? facts.borrowsThroughResult(id, input) : facts.borrowsInput(id, input);
                    if (!confined) throw new IllegalArgumentException("enum entry copied String input is not confined: " + id.linkage());
                    continue;
                }
                int position = input;
                if (conversions.parameters().get(id).stream().noneMatch(parameter -> parameter.input() == position)) {
                    throw new IllegalArgumentException("enum invocation reference input has no named conversion: " + type.displayName());
                }
                references.add(type);
            }
        }
        references.addAll(conversions.enumTypes());
        List<BridgeCallableId> proofRoots = new ArrayList<>(requested);
        proofRoots.addAll(conversions.initializers());
        var closure = BridgeRootSet.resolve(program, proofRoots);
        Map<IrType, BridgeNonReclamationContract> permanent = new LinkedHashMap<>();
        for (var type : references) {
            var proof = BridgeNonReclamationAnalyzer.analyze(program, closure, type, facts);
            if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
            permanent.put(type, proof.contract().orElseThrow());
        }
        var lifetime = new BridgePermanentContract(program, closure, permanent, Map.of());
        var retention = BridgeRetentionAnalyzer.copiedStringInputs(program, closure, facts, lifetime);
        for (var proof : retention.values()) {
            if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
        }
        Map<BridgeCallableId, BridgeStringResultContract> results = new LinkedHashMap<>();
        BridgeStringResults.proveForPermanent(artifact, closure, lifetime).forEach((id, proof) -> {
            if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
            results.put(id, proof.contract().orElseThrow());
        });
        return new BridgeEnumInvocation(entries, lifetime, conversions, results);
    }
}
