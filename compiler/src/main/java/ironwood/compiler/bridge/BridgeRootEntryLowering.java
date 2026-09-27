// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Protected creation/invocation with bounded final slots preserved before failure extraction. */
final class BridgeRootEntryLowering {
    private final BridgeRootSet.Root root;
    private final BridgeRetentionContract retention;
    private final SourceSpan span;
    private final List<IrBasicBlock> blocks = new ArrayList<>();
    private final List<IrOperand> arguments = new ArrayList<>();
    private IrValueReference frame;
    private int next;

    private BridgeRootEntryLowering(BridgeRootSet.Root root, BridgeRetentionContract retention) {
        this.root = root;
        this.retention = retention;
        this.span = root.span();
    }

    static IrFunction lower(BridgeRootSet.Root root, String symbol, BridgeRetentionContract retention, boolean initialize) {
        return new BridgeRootEntryLowering(root, retention).build(symbol, initialize);
    }

    private IrValueReference value(IrType type) { return new IrValueReference(next++, type, span); }

    private IrFunction build(String symbol, boolean initialize) {
        var callable = root.callable();
        boolean constructor = callable.kind() == IrCallableKind.CONSTRUCTOR;
        List<IrParameter> parameters = new ArrayList<>();
        for (int index = constructor ? 1 : 0; index < callable.parameters().size(); index++) {
            var type = callable.parameters().get(index);
            parameters.add(new IrParameter("argument" + index, value(type.equals(IrType.I1) ? IrType.I8 : type), span));
        }
        frame = value(IrType.I64);
        parameters.add(new IrParameter("resultFrame", frame, span));
        var created = constructor ? value(callable.parameters().getFirst()) : null;
        if (constructor) arguments.add(created);
        List<IrInstruction> preparation = new ArrayList<>();
        for (int index = constructor ? 1 : 0; index < callable.parameters().size(); index++) {
            var input = parameters.get(index - (constructor ? 1 : 0)).value();
            if (callable.parameters().get(index).equals(IrType.I1)) {
                var normalized = value(IrType.I1);
                preparation.add(new IrBinaryInstruction(normalized, IrBinaryOperator.NOT_EQUAL, input,
                        new IrConstant(IrType.I8, 0, span), span));
                arguments.add(normalized);
            } else arguments.add(input);
        }
        blocks.add(new IrBasicBlock("entry", preparation, initialize ? new IrInvokeTerminator(
                new IrEnsureTypeInitializedInstruction(callable.owner(), span), constructor ? "allocate" : "target", "failure.before", span)
                : new IrJump(constructor ? "allocate" : "target", span), span));
        if (constructor) blocks.add(new IrBasicBlock("allocate", List.of(), new IrInvokeTerminator(
                new IrAllocateInstruction(created, callable.owner(), span), "target", "failure.before", span), span));
        Optional<IrValueReference> result = callable.result().equals(IrType.VOID) ? Optional.empty() : Optional.of(value(callable.result()));
        blocks.add(new IrBasicBlock("target", List.of(), new IrInvokeTerminator(new IrCallInstruction(result,
                callable.linkage(), callable.result(), arguments, span), "success", constructor ? "failure.constructed" : "failure.before", span), span));
        List<IrInstruction> success = new ArrayList<>();
        if (constructor) success.add(new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.VALUE, created, span));
        result.ifPresent(value -> success.add(new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.VALUE, value, span)));
        blocks.add(new IrBasicBlock("success", success, new IrJump("success.slots.0", span), span));
        snapshots("success.slots", false, "return.success");
        blocks.add(new IrBasicBlock("return.success", List.of(), returned(0), span));
        failure("failure.before", constructor, null);
        if (constructor) failure("failure.constructed", true, created);
        blocks.add(new IrBasicBlock("snapshot.complete", List.of(), returned(1), span));
        var handle = value(IrType.EXCEPTION);
        var exception = value(IrType.EXCEPTION);
        blocks.add(new IrBasicBlock("snapshot.failure", List.of(new IrExceptionLandingPadInstruction(handle, exception, span),
                new IrExceptionCaughtInstruction(exception, span)), returned(2), span));
        return new IrFunction(callable.owner(), "<bridge-entry>", symbol, IrType.I32, parameters, blocks,
                span, root.sourceFile(), IrCallableKind.METHOD);
    }

    private void failure(String label, boolean omitConstructedRoot, IrValueReference rollback) {
        var handle = value(IrType.EXCEPTION);
        var exception = value(IrType.EXCEPTION);
        List<IrInstruction> failure = new ArrayList<>();
        failure.add(new IrExceptionLandingPadInstruction(handle, exception, span));
        failure.add(new IrExceptionCaughtInstruction(exception, span));
        failure.add(new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.EXCEPTION, exception, span));
        if (omitConstructedRoot) failure.add(new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.VALUE,
                new IrNull(root.callable().parameters().getFirst(), span), span));
        else if (root.callable().result().isReference()) failure.add(new IrBridgeResultStoreInstruction(frame,
                IrBridgeResultStoreInstruction.Slot.VALUE, new IrNull(root.callable().result(), span), span));
        if (rollback != null) failure.add(new IrRollbackInstruction(rollback, span));
        blocks.add(new IrBasicBlock(label, failure, new IrJump(label + ".slots.0", span), span));
        snapshots(label + ".slots", omitConstructedRoot, label + ".snapshot");
        blocks.add(new IrBasicBlock(label + ".snapshot", List.of(), new IrInvokeTerminator(
                new IrBridgeFailureSnapshotInstruction(exception, frame, span), "snapshot.complete", "snapshot.failure", span), span));
    }

    private void snapshots(String prefix, boolean omitConstructedRoot, String continuation) {
        for (int index = 0; index < retention.slots().size(); index++) {
            var slot = retention.slots().get(index);
            String label = prefix + "." + index;
            String nextLabel = prefix + "." + (index + 1);
            var absent = new IrBridgeSlotStoreInstruction(frame, index,
                    new IrNull(root.callable().parameters().get(slot.holderInput()), span), new IrNull(slot.field().type(), span), span);
            if (omitConstructedRoot && slot.holderInput() == 0) {
                blocks.add(new IrBasicBlock(label, List.of(absent), new IrJump(nextLabel, span), span));
                continue;
            }
            var holder = arguments.get(slot.holderInput());
            var present = value(IrType.I1);
            blocks.add(new IrBasicBlock(label, List.of(new IrNullCheckInstruction(present, holder, span)),
                    new IrBranch(present, label + ".read", label + ".absent", span), span));
            blocks.add(new IrBasicBlock(label + ".absent", List.of(absent), new IrJump(nextLabel, span), span));
            var finalValue = value(slot.field().type());
            blocks.add(new IrBasicBlock(label + ".read", List.of(new IrFieldLoadInstruction(finalValue, holder, slot.field(), span),
                    new IrBridgeSlotStoreInstruction(frame, index, holder, finalValue, span)), new IrJump(nextLabel, span), span));
        }
        blocks.add(new IrBasicBlock(prefix + "." + retention.slots().size(), List.of(), new IrJump(continuation, span), span));
    }

    private IrReturnTerminator returned(int status) {
        return new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, status, span)), span);
    }
}
