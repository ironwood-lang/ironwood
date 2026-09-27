// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeConstructionContract;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.ir.IrProgram;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable projection of the selected semantic analyses, bound to their IR. */
public final class BridgeConstructionFacts {
    private final IrProgram program;
    private final Map<BridgeCallableId, BridgeProof<BridgeConstructionContract>> constructors;
    private final Map<BridgeCallableId, Set<Integer>> borrowedInputs;
    private final Set<BridgeCallableId> staticCallables;

    private BridgeConstructionFacts(IrProgram program,
            Map<BridgeCallableId, BridgeProof<BridgeConstructionContract>> constructors,
            Map<BridgeCallableId, Set<Integer>> borrowedInputs, Set<BridgeCallableId> staticCallables) {
        this.program = program;
        this.constructors = Map.copyOf(constructors);
        this.borrowedInputs = Map.copyOf(borrowedInputs);
        this.staticCallables = Set.copyOf(staticCallables);
    }

    static BridgeConstructionFacts project(IrProgram raw, IrProgram specialized,
            Map<String, TypeSymbol> types, EscapeSummaryAnalyzer escapes,
            OwnedArrayFieldAnalyzer ownership) {
        Map<BridgeCallableId, BridgeProof<BridgeConstructionContract>> facts = new LinkedHashMap<>();
        Map<BridgeCallableId, Set<Integer>> borrowed = new LinkedHashMap<>();
        Set<BridgeCallableId> statics = new java.util.LinkedHashSet<>();
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
            if (unchanged && callable != null && summary != null) {
                if (callable.isStatic()) statics.add(id);
                Set<Integer> inputs = new java.util.LinkedHashSet<>();
                int offset = callable.isStatic() ? 0 : 1;
                for (int index = 0; index < callable.parameters().size(); index++) {
                    if (!summary.parameterEscapes(index)) inputs.add(index + offset);
                }
                if (!callable.isStatic() && !summary.thisEscapes()) inputs.add(0);
                borrowed.put(id, Set.copyOf(inputs));
            }
            if (!function.constructor()) continue;
            if (!unchanged || callable == null || owner == null || summary == null) {
                facts.put(id, BridgeProof.unknown("construction facts require unchanged resolved semantic effects"));
            } else if (summary.thisEscapes() || summary.thisEscapesWithoutReturn()
                    || !escapes.constructorCleanupIsConfined(callable)) {
                facts.put(id, BridgeProof.unknown("receiver publication or cleanup confinement is not proved"));
            } else {
                var fields = ownership.ownedInstanceFields(owner);
                Set<ironwood.compiler.ir.IrField> elements = fields.stream()
                        .filter(field -> OwnedArrayElementAnalyzer.fields(types.get(field.ownerClass())).contains(field))
                        .map(FieldSymbol::irField).collect(Collectors.toUnmodifiableSet());
                facts.put(id, BridgeProof.proved(new BridgeConstructionContract(id,
                        fields.stream().map(FieldSymbol::irField).toList(), elements),
                        "final escape, owned-field and closed-world construction validation passed"));
            }
        }
        return new BridgeConstructionFacts(specialized, facts, borrowed, statics);
    }

    public Map<BridgeCallableId, BridgeProof<BridgeConstructionContract>> constructors() {
        return constructors;
    }

    /** Final semantic proof that an input is neither retained, returned nor invalidated. */
    public boolean borrowsInput(BridgeCallableId callable, int input) {
        return borrowedInputs.getOrDefault(callable, Set.of()).contains(input);
    }

    public boolean isStatic(BridgeCallableId callable) { return staticCallables.contains(callable); }

    /** Any IR change requires fresh proof, including changes to callees and cleanup. */
    public boolean matches(IrProgram candidate) {
        return program.equals(candidate);
    }
}
