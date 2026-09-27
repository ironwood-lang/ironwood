// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Builds exact acquisition-prefix cleanup after BridgeEntryModule proves borrowing. */
final class BridgeStringEntryLowering {
    private BridgeStringEntryLowering() {}

    static IrFunction lower(BridgeRootSet.Root root, String symbol, boolean initialize,
            Optional<BridgeStringResultContract> resultContract) {
        var span = root.span();
        var callable = root.callable();
        List<IrParameter> parameters = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (var type : callable.parameters()) {
            starts.add(parameters.size());
            int id = parameters.size();
            var carrier = type.isReference() ? IrType.I64 : type.equals(IrType.I1) ? IrType.I8 : type;
            parameters.add(new IrParameter("argument" + id, new IrValueReference(id, carrier, span), span));
            if (type.isReference()) parameters.add(new IrParameter("length" + id,
                    new IrValueReference(id + 1, IrType.I32, span), span));
        }
        var frame = new IrValueReference(parameters.size(), IrType.I64, span);
        parameters.add(new IrParameter("resultFrame", frame, span));
        int next = parameters.size();
        List<IrInstruction> preparation = new ArrayList<>();
        List<IrOperand> arguments = new ArrayList<>();
        List<IrBridgeStringCopyInstruction> copies = new ArrayList<>();
        for (int index = 0; index < callable.parameters().size(); index++) {
            var type = callable.parameters().get(index);
            var input = parameters.get(starts.get(index)).value();
            if (type.isReference()) {
                var copy = new IrValueReference(next++, type, span);
                copies.add(new IrBridgeStringCopyInstruction(copy, input,
                        parameters.get(starts.get(index) + 1).value(), span));
                arguments.add(copy);
            } else if (type.equals(IrType.I1)) {
                var normalized = new IrValueReference(next++, IrType.I1, span);
                preparation.add(new IrBinaryInstruction(normalized, IrBinaryOperator.NOT_EQUAL, input,
                        new IrConstant(IrType.I8, 0, span), span));
                arguments.add(normalized);
            } else arguments.add(input);
        }
        List<IrBasicBlock> blocks = new ArrayList<>();
        blocks.add(new IrBasicBlock("entry", preparation, new IrJump(copies.isEmpty() ? "initialize" : "copy.0", span), span));
        for (int index = 0; index < copies.size(); index++) {
            blocks.add(new IrBasicBlock("copy." + index, List.of(), new IrInvokeTerminator(copies.get(index),
                    index + 1 == copies.size() ? "initialize" : "copy." + (index + 1), "failure." + index, span), span));
        }
        blocks.add(new IrBasicBlock("initialize", List.of(), initialize ? new IrInvokeTerminator(
                new IrEnsureTypeInitializedInstruction(callable.owner(), span), "target", "failure." + copies.size(), span)
                : new IrJump("target", span), span));
        Optional<IrValueReference> result = callable.result().equals(IrType.VOID) ? Optional.empty()
                : Optional.of(new IrValueReference(next++, callable.result(), span));
        boolean alias = resultContract.filter(contract -> contract.kind() == BridgeStringResultContract.Kind.INPUT_ALIAS).isPresent();
        blocks.add(new IrBasicBlock("target", List.of(), new IrInvokeTerminator(new IrCallInstruction(result,
                callable.linkage(), callable.result(), arguments, span), alias ? "cleanup.0" : "success",
                "failure." + copies.size(), span), span));
        List<IrInstruction> success = new ArrayList<>();
        for (int index = 0; index < copies.size(); index++) {
            var copy = copies.get(index);
            if (!alias) {
                success.add(new IrRawDeallocateInstruction(copy.result(), span));
                continue;
            }
            var retained = new IrValueReference(next++, IrType.I1, span);
            String after = index + 1 == copies.size() ? "success" : "cleanup." + (index + 1);
            blocks.add(new IrBasicBlock("cleanup." + index, List.of(new IrBinaryInstruction(retained,
                    IrBinaryOperator.EQUAL, copy.result(), result.orElseThrow(), span)),
                    new IrBranch(retained, after, "release." + index, span), span));
            blocks.add(new IrBasicBlock("release." + index, List.of(new IrRawDeallocateInstruction(copy.result(), span)),
                    new IrJump(after, span), span));
        }
        result.ifPresent(value -> success.add(new IrBridgeResultStoreInstruction(frame,
                IrBridgeResultStoreInstruction.Slot.VALUE, value, span)));
        blocks.add(new IrBasicBlock("success", success,
                new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 0, span)), span), span));
        for (int acquired = 0; acquired <= copies.size(); acquired++) {
            var handle = new IrValueReference(next++, IrType.EXCEPTION, span);
            var exception = new IrValueReference(next++, IrType.EXCEPTION, span);
            List<IrInstruction> failure = new ArrayList<>();
            failure.add(new IrExceptionLandingPadInstruction(handle, exception, span));
            failure.add(new IrExceptionCaughtInstruction(exception, span));
            for (int index = acquired - 1; index >= 0; index--) {
                failure.add(new IrRawDeallocateInstruction(copies.get(index).result(), span));
            }
            failure.add(new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.EXCEPTION, exception, span));
            blocks.add(new IrBasicBlock("failure." + acquired, failure,
                    new IrInvokeTerminator(new IrBridgeFailureSnapshotInstruction(exception, frame, span),
                            "snapshot.complete", "snapshot.failure", span), span));
        }
        blocks.add(new IrBasicBlock("snapshot.complete", List.of(),
                new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 1, span)), span), span));
        var snapshotHandle = new IrValueReference(next++, IrType.EXCEPTION, span);
        var snapshotException = new IrValueReference(next, IrType.EXCEPTION, span);
        blocks.add(new IrBasicBlock("snapshot.failure", List.of(new IrExceptionLandingPadInstruction(snapshotHandle, snapshotException, span),
                new IrExceptionCaughtInstruction(snapshotException, span)),
                new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 2, span)), span), span));
        return new IrFunction(callable.owner(), "<bridge-entry>", symbol, IrType.I32, parameters, blocks,
                span, root.sourceFile(), IrCallableKind.METHOD);
    }
}
