// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;

/** A narrow typed-body publication proof, independent of an interface signature. */
final class BridgeForeignReceiverConfinement {
    private BridgeForeignReceiverConfinement() {}

    static boolean proved(IrFunction function) {
        if (function.kind() != IrCallableKind.METHOD || function.parameters().isEmpty()
                || !function.parameters().getFirst().value().type().equals(IrType.reference(function.ownerClass()))
                || !(function.returnType().isPrimitive() || function.returnType().equals(IrType.VOID))
                || function.blocks().size() != 1) return false;
        var block = function.blocks().getFirst();
        if (block.instructions().size() != 2
                || !(block.instructions().getFirst() instanceof IrFieldLoadInstruction load)
                || !(block.instructions().get(1) instanceof IrForeignCallInstruction call)
                || !(block.terminator() instanceof IrReturnTerminator returned)) return false;
        if (!load.receiver().equals(function.parameters().getFirst().value())
                || !load.field().ownerClass().equals(function.ownerClass())
                || !load.field().type().equals(IrType.I64) || !load.result().type().equals(IrType.I64)
                || !call.returnType().equals(function.returnType())) return false;
        List<IrOperand> arguments = new ArrayList<>();
        arguments.add(load.result());
        function.parameters().stream().skip(1).map(IrParameter::value).forEach(arguments::add);
        // References among these explicit parameters remain unknown retaining
        // arguments. In particular, a caller passing the receiver again as a
        // parameter does not gain permission to reclaim that aliased object.
        if (!call.arguments().equals(arguments)) return false;
        return returned.value().equals(call.result().map(value -> (IrOperand) value));
    }

}
