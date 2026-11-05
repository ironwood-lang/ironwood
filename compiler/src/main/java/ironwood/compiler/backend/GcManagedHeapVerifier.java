// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import ironwood.compiler.ir.*;

/** Final GC-image invariant. Passing this check is not complete root admission. */
public final class GcManagedHeapVerifier {
    private GcManagedHeapVerifier() {}

    public static boolean containsCollection(IrProgram program) {
        return program.functions().stream().flatMap(f -> f.blocks().stream())
                .flatMap(b -> GcEffects.operations(b).stream()).anyMatch(IrGcCollectInstruction.class::isInstance);
    }

    public static void verify(IrProgram program) {
        for (IrClass type : program.classes()) {
            if (type.destructorChain().isPresent() || type.constructorRollback().isPresent()) {
                throw new IllegalArgumentException("GC image contains legacy managed destruction metadata: " + type.name());
            }
        }
        for (IrFunction function : program.functions()) {
            if (function.kind() == IrCallableKind.DESTRUCTOR || function.kind() == IrCallableKind.CONSTRUCTOR_ROLLBACK) {
                throw new IllegalArgumentException("GC image contains legacy managed cleanup function: " + function.linkageName());
            }
            for (IrBasicBlock block : function.blocks()) {
                for (IrInstruction instruction : GcEffects.operations(block)) {
                    if (instruction instanceof IrForeignCallInstruction || instruction instanceof IrByteViewInstruction
                            || instruction instanceof IrBridgeArrayCopyInstruction || instruction instanceof IrBridgeStringCopyInstruction
                            || instruction instanceof IrBridgeFailureSnapshotInstruction || instruction instanceof IrBridgeBatchAppendInstruction
                            || instruction instanceof IrBridgeResultStoreInstruction || instruction instanceof IrBridgeSlotStoreInstruction) {
                        throw new IllegalArgumentException("GC6 boundary: reachable foreign transition or bridge owner is not qualified: "
                                + instruction.getClass().getSimpleName());
                    }
                    if (instruction instanceof IrFreeInstruction || instruction instanceof IrRawDeallocateInstruction
                            || instruction instanceof IrRollbackInstruction || instruction instanceof IrDestroyArrayElementsInstruction
                            || instruction instanceof IrReleaseOwnedToStringResultInstruction
                            || instruction instanceof IrReleaseOwnedThrowableMessageInstruction
                            || instruction instanceof IrThrowableTraceInstruction trace
                            && trace.operation() == IrThrowableTraceInstruction.Operation.RELEASE) {
                        throw new IllegalArgumentException("GC image contains legacy managed deallocation "
                                + instruction.getClass().getSimpleName() + " in " + function.linkageName());
                    }
                }
            }
        }
    }
}
