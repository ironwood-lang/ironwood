// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrField;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bounded constructor-origin surface: reference inputs denote independently
 * constructed roots, results have uniform proved root origins, and every possible
 * retained-root edge follows an acyclic type graph. This is not a destruction
 * capability or permanent-storage proof.
 */
public record BridgeRootRetentionContract(IrProgram program, BridgeRootSet roots,
        Set<IrType> constructedRootTypes,
        Map<BridgeCallableId, BridgeRetentionContract> entries,
        Map<IrType, List<IrField>> rootSlots,
        Map<IrType, Set<IrType>> dependencies,
        Map<BridgeCallableId, BridgeResultOriginContract> resultOrigins) {
    public BridgeRootRetentionContract {
        constructedRootTypes = Set.copyOf(constructedRootTypes);
        entries = Map.copyOf(entries);
        resultOrigins = Map.copyOf(resultOrigins);
        rootSlots = rootSlots.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        dependencies = dependencies.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    public boolean matches(IrProgram candidate, BridgeRootSet requested) {
        return program.equals(candidate) && roots.equals(requested.revalidate(candidate));
    }
}
