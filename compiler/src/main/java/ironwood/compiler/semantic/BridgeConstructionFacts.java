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

    private BridgeConstructionFacts(IrProgram program,
            Map<BridgeCallableId, BridgeProof<BridgeConstructionContract>> constructors) {
        this.program = program;
        this.constructors = Map.copyOf(constructors);
    }

    static BridgeConstructionFacts project(IrProgram raw, IrProgram specialized,
            Map<String, TypeSymbol> types, EscapeSummaryAnalyzer escapes,
            OwnedArrayFieldAnalyzer ownership) {
        Map<BridgeCallableId, BridgeProof<BridgeConstructionContract>> facts = new LinkedHashMap<>();
        // Specialization may change transitive effects as well as the entry body.
        // Until those effects are re-proved, do not transplant source facts.
        boolean unchanged = raw.functions().equals(specialized.functions())
                && raw.classes().equals(specialized.classes())
                && raw.dispatchSlots().equals(specialized.dispatchSlots())
                && raw.typeInitializations().equals(specialized.typeInitializations());
        for (var function : specialized.functions()) {
            if (!function.constructor()) continue;
            var id = BridgeCallableId.of(function);
            var callable = escapes.callable(function.linkageName());
            var owner = types.get(function.ownerClass());
            var summary = escapes.summary(function.linkageName());
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
        return new BridgeConstructionFacts(specialized, facts);
    }

    public Map<BridgeCallableId, BridgeProof<BridgeConstructionContract>> constructors() {
        return constructors;
    }

    /** Any IR change requires fresh proof, including changes to callees and cleanup. */
    public boolean matches(IrProgram candidate) {
        return program.equals(candidate);
    }
}
