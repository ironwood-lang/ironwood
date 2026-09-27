// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.IrProgram;

/** Shared post-validation transforms for executable and explicit library roots. */
final class NativeLinkPipeline {
    private NativeLinkPipeline() {}

    static IrProgram optimize(IrProgram program) {
        var initialized = InitializedTypeSpecializer.specialize(program);
        var enums = EnumArgumentSpecializer.specialize(initialized);
        return FieldValueForwarder.forward(enums);
    }

    static IrProgram finish(IrProgram program) {
        return UnreadFieldStoreEliminator.eliminate(ClosedWorldPruner.prune(program));
    }
}
