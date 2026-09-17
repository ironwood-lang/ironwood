// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrTlsInstruction;

/** Recomputed from the specialized, pruned typed program at every final link. */
public record NativeLinkRequirements(boolean tls) {
    public static final NativeLinkRequirements NONE = new NativeLinkRequirements(false);

    public static NativeLinkRequirements from(IrProgram program) {
        // The pruner's retained functions already include invoke targets, dispatch,
        // initialization and exceptional cleanup edges. Intrinsics have typed bodies.
        return new NativeLinkRequirements(program.functions().stream()
                .flatMap(function -> function.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .anyMatch(IrTlsInstruction.class::isInstance));
    }
}
