// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;

import java.util.Map;

/** Uniformly permanent references need no root lifetime state or retention commit. */
public record BridgePermanentContract(IrProgram program, BridgeRootSet roots,
        Map<IrType, BridgeNonReclamationContract> references,
        Map<BridgeCallableId, BridgeCleanupContract> rollbacks) {
    public BridgePermanentContract {
        references = Map.copyOf(references);
        rollbacks = Map.copyOf(rollbacks);
    }

    public boolean matches(IrProgram candidate, BridgeRootSet requested) {
        return program.equals(candidate) && roots.equals(requested.revalidate(candidate));
    }
}
