// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;

import ironwood.compiler.bridge.BridgeCallbackAbi;

/** A narrow typed-body publication proof, independent of an interface signature. */
final class BridgeForeignReceiverConfinement {
    private BridgeForeignReceiverConfinement() {}

    static boolean proved(IrFunction function) {
        if (function.kind() != IrCallableKind.METHOD || function.parameters().isEmpty()
                || !function.parameters().getFirst().value().type().equals(IrType.reference(function.ownerClass()))
                || !(function.returnType().isPrimitive() || function.returnType().equals(IrType.VOID))
                || function.blocks().isEmpty()) return false;
        var block = function.blocks().getFirst();
        if (block.instructions().isEmpty()
                || !(block.instructions().getFirst() instanceof IrFieldLoadInstruction load)
                || !load.field().ownerClass().equals(function.ownerClass())
                || !load.field().type().equals(IrType.I64)) return false;
        var calls = function.blocks().stream().flatMap(value -> value.instructions().stream())
                .filter(IrForeignCallInstruction.class::isInstance).map(IrForeignCallInstruction.class::cast).toList();
        if (calls.size() != 1) return false;
        // Match every operation and edge, including typed primitive normalization.
        // Explicit reference arguments still cross the unknown retaining call;
        // this proves only that the hidden receiver itself is not published.
        return function.blocks().equals(BridgeCallbackAbi.body(function, load.field(), calls.getFirst().targetLinkageName()));
    }
}
