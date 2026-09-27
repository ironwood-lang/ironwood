// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeCallTargets;
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
    public record Parameter(int input, IrType declaredType, List<BridgeEnumConstants.Constant> constants, boolean nullable) {
        public Parameter { constants = List.copyOf(constants); }
    }

    private static final IrType STRING = IrType.reference("ironwood.lang.String");
    private final BridgeRootSet entries;
    private final BridgePermanentContract lifetime;
    private final Map<BridgeCallableId, List<Parameter>> parameters;
    private final Map<BridgeCallableId, BridgeStringResultContract> stringResults;

    private BridgeEnumInvocation(BridgeRootSet entries, BridgePermanentContract lifetime,
            Map<BridgeCallableId, List<Parameter>> parameters, Map<BridgeCallableId, BridgeStringResultContract> stringResults) {
        this.entries = entries;
        this.lifetime = lifetime;
        this.parameters = Map.copyOf(parameters);
        this.stringResults = Map.copyOf(stringResults);
    }

    public BridgeRootSet entries() { return entries; }
    public BridgePermanentContract lifetime() { return lifetime; }
    public Map<BridgeCallableId, List<Parameter>> parameters() { return parameters; }
    public Map<BridgeCallableId, BridgeStringResultContract> stringResults() { return stringResults; }

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
        Map<BridgeCallableId, Parameter> receivers = new LinkedHashMap<>();
        for (var dispatch : dispatches) {
            if (!dispatch.matches(program, dispatch.type(), dispatch.method())
                    || !constants.constants().containsKey(dispatch.type())) {
                throw new IllegalArgumentException("enum invocation has stale dispatch metadata");
            }
            for (var target : dispatch.targets()) {
                if (!constants.constants().get(dispatch.type()).contains(target.constant())) {
                    throw new IllegalArgumentException("enum invocation token pairing differs from dispatch metadata");
                }
                if (target.javaIdentity()) continue;
                var prior = receivers.get(target.callable());
                if (prior != null && !prior.declaredType().equals(dispatch.type())) {
                    throw new IllegalArgumentException("enum body is shared by incompatible receiver types");
                }
                var allowed = new ArrayList<>(prior == null ? List.of() : prior.constants());
                if (!allowed.contains(target.constant())) allowed.add(target.constant());
                receivers.put(target.callable(), new Parameter(0, dispatch.type(), allowed, false));
            }
        }
        var requested = new LinkedHashSet<>(receivers.keySet());
        for (var id : staticEntries) {
            if (!facts.isStatic(id) || id.kind() != IrCallableKind.METHOD) {
                throw new IllegalArgumentException("enum static entry requires an exact static method");
            }
            requested.add(id);
        }
        var entries = BridgeRootSet.resolve(program, List.copyOf(requested));
        if (!entries.resolved()) throw new IllegalArgumentException("enum invocation requires resolved native bodies");
        Map<BridgeCallableId, List<Parameter>> parameters = new LinkedHashMap<>();
        Set<IrType> usedEnums = new LinkedHashSet<>();
        Set<IrType> references = new LinkedHashSet<>();
        for (var root : entries.roots()) {
            var id = root.callable();
            if (id.result().isReference() && !id.result().equals(STRING)) {
                throw new IllegalArgumentException("enum/object result conversion is not implemented in this invocation mode");
            }
            List<Parameter> converted = new ArrayList<>();
            for (int input = 0; input < id.parameters().size(); input++) {
                var type = id.parameters().get(input);
                if (!type.isReference()) continue;
                if (type.equals(STRING)) {
                    boolean confined = id.result().equals(STRING) ? facts.borrowsThroughResult(id, input) : facts.borrowsInput(id, input);
                    if (!confined) throw new IllegalArgumentException("enum entry copied String input is not confined: " + id.linkage());
                    continue;
                }
                Parameter receiver = input == 0 ? receivers.get(id) : null;
                if (receiver != null) converted.add(receiver);
                else if (constants.constants().containsKey(type)) {
                    converted.add(new Parameter(input, type, constants.constants().get(type), true));
                } else throw new IllegalArgumentException("enum invocation reference input has no named conversion: " + type.displayName());
                usedEnums.add(converted.getLast().declaredType());
                references.add(type);
            }
            parameters.put(id, List.copyOf(converted));
        }
        references.addAll(usedEnums);
        List<BridgeCallableId> proofRoots = new ArrayList<>(requested);
        var targets = new BridgeCallTargets(program);
        for (var type : usedEnums) {
            var initialization = targets.initializers(type.referenceName());
            if (!initialization.complete()) throw new IllegalArgumentException("incomplete enum conversion initialization");
            initialization.targets().stream().map(BridgeCallableId::of).forEach(proofRoots::add);
        }
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
        return new BridgeEnumInvocation(entries, lifetime, parameters, results);
    }
}
