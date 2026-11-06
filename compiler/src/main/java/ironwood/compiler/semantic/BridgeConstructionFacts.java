// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.NativeLinkTransformation;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeEntryModule;
import ironwood.compiler.bridge.BridgeExceptionEntries;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeResultOriginContract;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrCallableKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable projection of the selected semantic analyses, bound to their IR. */
public final class BridgeConstructionFacts {
    private final IrProgram program;

    private final Map<BridgeCallableId, Set<Integer>> borrowedInputs;
    private final Map<BridgeCallableId, Set<Integer>> returnOnlyInputs;
    private final Set<BridgeCallableId> staticCallables;
    private final Set<BridgeCallableId> finalCallables;
    private final Set<BridgeCallableId> constructibleConstructors;
    private final Map<BridgeCallableId, BridgeProof<BridgeResultOriginContract>> resultOrigins;

    private final Map<String, Set<ironwood.compiler.ir.IrType>> genericAllocations;

    private BridgeConstructionFacts(IrProgram program,
            Map<BridgeCallableId, Set<Integer>> borrowedInputs,
            Map<BridgeCallableId, Set<Integer>> returnOnlyInputs, Set<BridgeCallableId> staticCallables,
            Set<BridgeCallableId> finalCallables, Set<BridgeCallableId> constructibleConstructors,
            Map<BridgeCallableId, BridgeProof<BridgeResultOriginContract>> resultOrigins,
            Map<String, Set<ironwood.compiler.ir.IrType>> genericAllocations) {
        this.program = program;
        this.borrowedInputs = Map.copyOf(borrowedInputs);
        this.returnOnlyInputs = Map.copyOf(returnOnlyInputs);
        this.staticCallables = Set.copyOf(staticCallables);
        this.finalCallables = Set.copyOf(finalCallables);
        this.constructibleConstructors = Set.copyOf(constructibleConstructors);
        this.resultOrigins = Map.copyOf(resultOrigins);
        this.genericAllocations = Map.copyOf(genericAllocations);
    }

    static BridgeConstructionFacts project(IrProgram raw, IrProgram specialized,
            Map<String, TypeSymbol> types, EscapeSummaryAnalyzer escapes) {
        Map<BridgeCallableId, Set<Integer>> borrowed = new LinkedHashMap<>();
        Map<BridgeCallableId, Set<Integer>> returnOnly = new LinkedHashMap<>();
        Set<BridgeCallableId> statics = new java.util.LinkedHashSet<>();
        Set<BridgeCallableId> finals = new java.util.LinkedHashSet<>();
        Set<BridgeCallableId> constructible = new java.util.LinkedHashSet<>();
        Map<BridgeCallableId, BridgeProof<BridgeResultOriginContract>> results = new LinkedHashMap<>();
        // Specialization may change transitive effects as well as the entry body.
        // Until those effects are re-proved, do not transplant source facts.
        boolean unchanged = raw.functions().equals(specialized.functions())
                && raw.classes().equals(specialized.classes())
                && raw.dispatchSlots().equals(specialized.dispatchSlots())
                && raw.typeInitializations().equals(specialized.typeInitializations());
        for (var function : specialized.functions()) {
            var id = BridgeCallableId.of(function);
            var callable = escapes.callable(function.linkageName());
            var owner = types.get(function.ownerClass());
            var summary = escapes.summary(function.linkageName());
            if (id.result().isReference()) {
                results.put(id, unchanged && callable != null && summary != null
                        ? BridgeResultOrigins.project(id, callable, summary)
                        : BridgeProof.unknown("result origins require unchanged resolved semantic effects"));
            }
            if (unchanged && callable != null && summary != null) {
                if (callable.isStatic()) statics.add(id);
                if (callable.isFinal()) finals.add(id);
                if (function.constructor() && owner != null && !owner.isInterface() && !owner.isAbstract()
                        && !owner.isEnum() && !owner.isEnumConstantClass()) constructible.add(id);
                Set<Integer> inputs = new java.util.LinkedHashSet<>();
                Set<Integer> returnedInputs = new java.util.LinkedHashSet<>();
                int offset = callable.isStatic() ? 0 : 1;
                for (int index = 0; index < callable.parameters().size(); index++) {
                    if (!summary.parameterEscapes(index)) inputs.add(index + offset);
                    if (!summary.parameterEscapesWithoutReturn(index)) returnedInputs.add(index + offset);
                }
                if (!callable.isStatic() && !summary.thisEscapes()) inputs.add(0);
                if (!callable.isStatic() && !summary.thisEscapesWithoutReturn()) returnedInputs.add(0);
                borrowed.put(id, Set.copyOf(inputs));
                returnOnly.put(id, Set.copyOf(returnedInputs));
            }

        }
        return new BridgeConstructionFacts(specialized, borrowed, returnOnly, statics, finals, constructible, results,
                unchanged ? BridgeGenericAllocations.project(raw, types) : Map.of());
    }

    /** Context specialization changes dispatch. Do not project obsolete ownership or
     * borrowing facts onto that transformed managed program. Native/resource proofs
     * were checked against the original source before these entries were composed.
     */
    public BridgeConstructionFacts forManagedBridge(ironwood.compiler.CompilationArtifact artifact,
            BridgeEntryModule entries, BridgeExceptionEntries exceptions) {
        if (!matches(artifact.program().orElseThrow()) || !entries.matchesOriginal(artifact))
            throw new IllegalArgumentException("managed construction facts require exact source and managed entries");
        ironwood.compiler.backend.GcManagedHeapVerifier.verifyBridge(exceptions.program());
        return new BridgeConstructionFacts(exceptions.program(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of());
    }

    /** Carry conservative facts only through an execution of the fixed native passes. */
    public BridgeConstructionFacts afterNativeLink(NativeLinkTransformation transformation) {
        if (!transformation.startsWith(program)) {
            throw new IllegalArgumentException("native-link facts require the exact analyzed input program");
        }
        Map<BridgeCallableId, Set<Integer>> borrowed = new LinkedHashMap<>();
        Map<BridgeCallableId, Set<Integer>> returned = new LinkedHashMap<>();
        Set<BridgeCallableId> statics = new java.util.LinkedHashSet<>();
        Set<BridgeCallableId> finals = new java.util.LinkedHashSet<>();
        Set<BridgeCallableId> constructible = new java.util.LinkedHashSet<>();
        Map<BridgeCallableId, BridgeProof<BridgeResultOriginContract>> results = new LinkedHashMap<>();
        transformation.origins().forEach((target, source) -> {
            if (borrowedInputs.containsKey(source)) borrowed.put(target, borrowedInputs.get(source));
            if (returnOnlyInputs.containsKey(source)) returned.put(target, returnOnlyInputs.get(source));
            if (staticCallables.contains(source)) statics.add(target);
            if (finalCallables.contains(source)) finals.add(target);
            if (constructibleConstructors.contains(source)) constructible.add(target);
            if (resultOrigins.containsKey(source)) {
                results.put(target, target.equals(source) ? resultOrigins.get(source)
                        : BridgeProof.unknown("specialized result parameter provenance requires separate proof"));
            }
        });
        // No effect query is skipped: consumers re-scan final calls, dispatch,
        // protected entries and JNI completion. Unknown source facts stay unknown.
        return new BridgeConstructionFacts(transformation.program(), borrowed, returned,
                statics, finals, constructible, results, genericAllocations);
    }

    Set<ironwood.compiler.ir.IrType> genericAlternatives(ironwood.compiler.ir.IrType type,
            ironwood.compiler.bridge.BridgeRootSet roots) {
        if (!type.isTypeParameter()) return Set.of();
        // A direct private construction root could manufacture applications not
        // present in source allocations. Generated constructor entries inherit
        // that same restriction; no export permission follows from this fact.
        for (var root : roots.roots()) {
            var id = root.callable();
            if (id.kind() == IrCallableKind.CONSTRUCTOR && !id.parameters().isEmpty()
                    && id.parameters().getFirst().typeArguments().contains(type)) return Set.of();
            if (id.parameters().contains(type)) return Set.of();
        }
        return genericAllocations.getOrDefault(type.referenceName(), Set.of());
    }

    /** Final semantic proof that an input is neither retained, returned nor invalidated. */
    public boolean borrowsInput(BridgeCallableId callable, int input) {
        return borrowedInputs.getOrDefault(callable, Set.of()).contains(input);
    }

    /** The input must stay alive until its returned aliases have been consumed. */
    public boolean borrowsThroughResult(BridgeCallableId callable, int input) {
        return returnOnlyInputs.getOrDefault(callable, Set.of()).contains(input);
    }

    public boolean isStatic(BridgeCallableId callable) { return staticCallables.contains(callable); }

    public boolean isFinal(BridgeCallableId callable) { return finalCallables.contains(callable); }

    public boolean isConstructibleConstructor(BridgeCallableId callable) { return constructibleConstructors.contains(callable); }

    public Map<BridgeCallableId, BridgeProof<BridgeResultOriginContract>> resultOrigins() { return resultOrigins; }

    /** Any IR change requires fresh proof, including changes to callees and cleanup. */
    public boolean matches(IrProgram candidate) {
        return program.equals(candidate);
    }
}
