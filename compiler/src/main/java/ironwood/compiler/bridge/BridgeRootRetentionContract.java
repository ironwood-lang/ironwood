// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;

import java.util.Map;
import java.util.Set;

/** Exact source identities, anchor types, listener slots and result origins. */
public record BridgeRootRetentionContract(IrProgram program, BridgeRootSet roots,
        Set<IrType> constructedRootTypes,
        Map<BridgeCallableId, BridgeRetentionContract> entries,
        Map<BridgeCallableId, BridgeResultOriginContract> resultOrigins) {
    public BridgeRootRetentionContract {
        constructedRootTypes = Set.copyOf(constructedRootTypes);
        entries = Map.copyOf(entries);
        resultOrigins = Map.copyOf(resultOrigins);
    }

    public boolean matches(IrProgram candidate, BridgeRootSet requested) {
        return program.equals(candidate) && roots.equals(requested.revalidate(candidate));
    }

    public BridgeRootSet analysisRoots() { return roots; }
}
