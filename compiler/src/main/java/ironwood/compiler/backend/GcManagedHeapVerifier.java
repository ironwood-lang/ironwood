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
        verify(program, false);
    }

    static void verify(IrProgram program, boolean foreignSegments) {
        verify(program, foreignSegments, false);
    }

    public static void verifyBridge(IrProgram program) {
        verify(program, true, true);
    }

    private static void verify(IrProgram program, boolean foreignSegments, boolean bridgeOwners) {
        for (IrFunction function : program.functions()) {

            for (IrBasicBlock block : function.blocks()) {
                for (IrInstruction instruction : GcEffects.operations(block)) {
                    if (instruction instanceof IrExceptionLandingPadInstruction pad && !pad.exceptionObject().type().isReference()
                            || instruction instanceof IrExceptionCaughtInstruction caught && !caught.exception().type().isReference()
                            || instruction instanceof IrBridgeResultStoreInstruction store && store.slot() == IrBridgeResultStoreInstruction.Slot.EXCEPTION
                                && !store.value().type().isReference()
                            || instruction instanceof IrBridgeFailureSnapshotInstruction snapshot && !snapshot.exception().type().isReference()) {
                        throw new IllegalArgumentException("GC image requires a typed managed exception object in " + function.linkageName());
                    }
                    if (instruction instanceof IrAllocateInstruction allocation && allocation.result().type().equals(IrByteViewInstruction.TYPE)
                            || instruction instanceof IrGcRootSlotInstruction slot && slot.value().type().equals(IrByteViewInstruction.TYPE)
                            || instruction instanceof IrBridgeResultStoreInstruction store && store.value().type().equals(IrByteViewInstruction.TYPE)) {
                        throw new IllegalArgumentException("borrowed native descriptor cannot be a managed allocation, root or result");
                    }
                    if (instruction instanceof IrForeignCallInstruction && !foreignSegments || !bridgeOwners && (instruction instanceof IrByteViewInstruction
                            || instruction instanceof IrBridgeArrayCopyInstruction || instruction instanceof IrBridgeStringCopyInstruction
                            || instruction instanceof IrBridgeFailureSnapshotInstruction || instruction instanceof IrBridgeBatchAppendInstruction
                            || instruction instanceof IrBridgeResultStoreInstruction || instruction instanceof IrBridgeSlotStoreInstruction)) {
                        throw new IllegalArgumentException("GC6 boundary: reachable foreign transition or bridge owner is not qualified: "
                                + instruction.getClass().getSimpleName());
                    }
                    if (instruction instanceof IrCallInstruction call && legacyManagedSymbol(call.targetLinkageName())
                            || instruction instanceof IrForeignCallInstruction foreign && legacyManagedSymbol(foreign.targetLinkageName())) {
                        throw new IllegalArgumentException("GC image contains legacy managed deallocation in " + function.linkageName());
                    }
                }
            }
        }
    }
    private static boolean legacyManagedSymbol(String symbol) {
        return java.util.Set.of("free", "ironwood_deallocate", "ironwood_release_owned_to_string_result",
                "ironwood_release_owned_throwable_message", "ironwood_destroy_array_elements", "ironwood_throwable_trace_release", "ironwood_destructor_failed", "ironwood.destroy",
                "ironwood.rollback", "ironwood.destroy_array_elements").contains(symbol);
    }
}
