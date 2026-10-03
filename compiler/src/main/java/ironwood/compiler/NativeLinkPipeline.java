// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrFunction;

/** Shared post-validation transforms for executable and explicit library roots. */
final class NativeLinkPipeline {
    private NativeLinkPipeline() {}

    static IrProgram optimize(IrProgram program) {
        return optimize(program, (original, copy) -> {});
    }

    static IrProgram optimize(IrProgram program, java.util.function.BiConsumer<IrFunction, IrFunction> cloned) {
        var initialized = InitializedTypeSpecializer.specialize(program, cloned);
        var enums = EnumArgumentSpecializer.specialize(initialized, cloned);
        return FieldValueForwarder.forward(enums);
    }

    static IrProgram finish(IrProgram program) {
        return UnreadFieldStoreEliminator.eliminate(ClosedWorldPruner.prune(program));
    }
}
