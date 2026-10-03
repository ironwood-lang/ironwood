// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shared protected native entry mechanics. Callers must separately establish admission. */
public final class BridgeProtectedEntryLowering {
    private BridgeProtectedEntryLowering() {}

    public static IrFunction lower(BridgeRootSet.Root root, String symbol, boolean initialize) {
        var span = root.span();
        var callable = root.callable();
        List<IrParameter> parameters = new ArrayList<>();
        for (var type : callable.parameters()) {
            int id = parameters.size();
            var value = new IrValueReference(id, type.equals(IrType.I1) ? IrType.I8 : type, span);
            parameters.add(new IrParameter("argument" + id, value, span));
        }
        var frame = new IrValueReference(parameters.size(), IrType.I64, span);
        parameters.add(new IrParameter("resultFrame", frame, span));
        int next = parameters.size();
        List<IrInstruction> preparation = new ArrayList<>();
        List<IrOperand> arguments = new ArrayList<>();
        for (int index = 0; index < callable.parameters().size(); index++) {
            var input = parameters.get(index).value();
            if (callable.parameters().get(index).equals(IrType.I1)) {
                var normalized = new IrValueReference(next++, IrType.I1, span);
                preparation.add(new IrBinaryInstruction(normalized, IrBinaryOperator.NOT_EQUAL, input,
                        new IrConstant(IrType.I8, 0, span), span));
                arguments.add(normalized);
            } else arguments.add(input);
        }
        Optional<IrValueReference> result = callable.result().equals(IrType.VOID) ? Optional.empty()
                : Optional.of(new IrValueReference(next++, callable.result(), span));
        var handle = new IrValueReference(next++, IrType.EXCEPTION, span);
        var exception = new IrValueReference(next++, IrType.EXCEPTION, span);
        var snapshotHandle = new IrValueReference(next++, IrType.EXCEPTION, span);
        var snapshotException = new IrValueReference(next, IrType.EXCEPTION, span);
        List<IrBasicBlock> blocks = List.of(
                new IrBasicBlock("entry", preparation, initialize ? new IrInvokeTerminator(
                        new IrEnsureTypeInitializedInstruction(callable.owner(), span), "target", "failure", span)
                        : new IrJump("target", span), span),
                new IrBasicBlock("target", List.of(), new IrInvokeTerminator(
                        new IrCallInstruction(result, callable.linkage(), callable.result(), arguments, span),
                        "success", "failure", span), span),
                new IrBasicBlock("success", result.<List<IrInstruction>>map(value -> List.of(
                        new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.VALUE, value, span)))
                        .orElse(List.of()), new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 0, span)), span), span),
                new IrBasicBlock("failure", List.of(new IrExceptionLandingPadInstruction(handle, exception, span),
                        new IrExceptionCaughtInstruction(exception, span),
                        new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.EXCEPTION, exception, span)),
                        new IrInvokeTerminator(new IrBridgeFailureSnapshotInstruction(exception, frame, span),
                                "snapshot.complete", "snapshot.failure", span), span),
                new IrBasicBlock("snapshot.complete", List.of(),
                        new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 1, span)), span), span),
                new IrBasicBlock("snapshot.failure", List.of(
                        new IrExceptionLandingPadInstruction(snapshotHandle, snapshotException, span),
                        new IrExceptionCaughtInstruction(snapshotException, span)),
                        new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 2, span)), span), span));
        return new IrFunction(callable.owner(), "<bridge-entry>", symbol, IrType.I32, parameters, blocks,
                span, root.sourceFile(), IrCallableKind.METHOD);
    }
}
