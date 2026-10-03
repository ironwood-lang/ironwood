// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeResultOriginContract;

import java.util.LinkedHashSet;
import java.util.Set;

/** Projects final selected summaries; never recomputes or relaxes ordinary escape effects. */
final class BridgeResultOrigins {
    private BridgeResultOrigins() {}

    static BridgeProof<BridgeResultOriginContract> project(BridgeCallableId id, CallableSymbol callable,
            EscapeSummaryAnalyzer.EscapeSummary summary) {
        boolean alias = !summary.returnedOrigins().isEmpty();
        boolean dependent = !summary.borrowedReturnedOrigins().isEmpty();
        if (summary.mayReturnFresh() && (alias || dependent)) {
            return BridgeProof.rejected("mixed fresh/existing reclaimable result origins require distinct exports");
        }
        if (summary.mayReturnNonOrigin()) return BridgeProof.unknown("result has an unknown semantic origin");
        if (summary.mayReturnFresh()) {
            if (summary.freshEscapes()) return BridgeProof.rejected("fresh result publication is not confined to its return");
            return proved(id, BridgeResultOriginContract.Kind.FRESH_ROOT, Set.of(), summary.mayReturnNull());
        }
        if (alias && dependent) return BridgeProof.rejected("result mixes root aliases and dependent storage");
        Set<Integer> inputs = new LinkedHashSet<>();
        if (alias) {
            for (var origin : summary.returnedOrigins()) {
                int index = input(origin, callable);
                if (index < 0) return BridgeProof.unknown("array element result has no root origin proof");
                inputs.add(index);
            }
            // Reference inputs can themselves be null even without an explicit null return.
            return proved(id, BridgeResultOriginContract.Kind.INPUT_ALIAS, inputs, true);
        }
        if (dependent) {
            for (var origin : summary.borrowedReturnedOrigins()) {
                int index = input(origin.ownerOrigin(), callable);
                if (index < 0 || origin.borrowedOwnerField() != null
                        || !id.result().isNominalReference() || !origin.helperType().equals(id.result().referenceName())) {
                    return BridgeProof.unknown("dependent result requires an exact owned-storage origin");
                }
                inputs.add(index);
            }
            if (inputs.size() != 1) return BridgeProof.rejected("dependent result has multiple possible owner inputs");
            // Ownership proves lifetime, not that the owned field is initialized non-null.
            return proved(id, BridgeResultOriginContract.Kind.DEPENDENT_VIEW, inputs, true);
        }
        if (summary.mayReturnNull()) return proved(id, BridgeResultOriginContract.Kind.NULL_ONLY, Set.of(), true);
        return BridgeProof.unknown("no complete reference-return alternative is established");
    }

    private static int input(ReturnOrigin origin, CallableSymbol callable) {
        return switch (origin.kind()) {
            case THIS -> callable.isStatic() ? -1 : 0;
            case PARAMETER -> origin.parameterIndex() + (callable.isStatic() ? 0 : 1);
            case ELEMENT_OF_PARAMETER -> -1;
        };
    }

    private static BridgeProof<BridgeResultOriginContract> proved(BridgeCallableId id,
            BridgeResultOriginContract.Kind kind, Set<Integer> inputs, boolean nullable) {
        return BridgeProof.proved(new BridgeResultOriginContract(id, kind, inputs, nullable),
                "selected final symbolic-return and escape summaries establish uniform origins");
    }
}
