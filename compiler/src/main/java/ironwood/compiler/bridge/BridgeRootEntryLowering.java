// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Protected creation/invocation with bounded final slots preserved before failure extraction. */
final class BridgeRootEntryLowering {
    private static final IrType STRING = IrType.reference("ironwood.lang.String");
    private final BridgeRootSet.Root root;
    private final BridgeRetentionContract retention;
    private final SourceSpan span;
    private final List<IrBasicBlock> blocks = new ArrayList<>();
    private final List<IrOperand> arguments = new ArrayList<>();
    private final List<IrBridgeStringCopyInstruction> copies = new ArrayList<>();
    private final Optional<BridgeStringResultContract> stringResult;
    private final Map<Integer, BridgeEnumInvocation.Parameter> enums;
    private IrValueReference frame;
    private int next;

    private BridgeRootEntryLowering(BridgeRootSet.Root root, BridgeRetentionContract retention,
            Optional<BridgeStringResultContract> stringResult, List<BridgeEnumInvocation.Parameter> enumParameters) {
        this.root = root;
        this.retention = retention;
        this.span = root.span();
        this.stringResult = stringResult;
        this.enums = enumParameters.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                BridgeEnumInvocation.Parameter::input, parameter -> parameter));
    }

    static IrFunction lower(BridgeRootSet.Root root, String symbol, BridgeRetentionContract retention, boolean initialize) {
        return lower(root, symbol, retention, initialize, Optional.empty());
    }

    static IrFunction lower(BridgeRootSet.Root root, String symbol, BridgeRetentionContract retention, boolean initialize,
            Optional<BridgeStringResultContract> stringResult) {
        return lower(root, symbol, retention, initialize, stringResult, List.of());
    }

    static IrFunction lower(BridgeRootSet.Root root, String symbol, BridgeRetentionContract retention, boolean initialize,
            Optional<BridgeStringResultContract> stringResult, List<BridgeEnumInvocation.Parameter> enumParameters) {
        if (root.callable().result().equals(STRING) != stringResult.isPresent()
                || stringResult.isPresent() && !stringResult.orElseThrow().callable().equals(root.callable())) {
            throw new IllegalArgumentException("root String lowering requires the exact result contract");
        }
        return new BridgeRootEntryLowering(root, retention, stringResult, enumParameters).build(symbol, initialize);
    }

    private IrValueReference value(IrType type) { return new IrValueReference(next++, type, span); }

    private IrFunction build(String symbol, boolean initialize) {
        var callable = root.callable();
        boolean constructor = callable.kind() == IrCallableKind.CONSTRUCTOR;
        List<IrParameter> parameters = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (int index = constructor ? 1 : 0; index < callable.parameters().size(); index++) {
            var type = callable.parameters().get(index);
            starts.add(parameters.size());
            parameters.add(new IrParameter("argument" + index,
                    value(enums.containsKey(index) ? IrType.I32 : type.equals(STRING) ? IrType.I64
                            : type.equals(IrType.I1) ? IrType.I8 : type), span));
            if (type.equals(STRING)) parameters.add(new IrParameter("length" + index, value(IrType.I32), span));
        }
        frame = value(IrType.I64);
        parameters.add(new IrParameter("resultFrame", frame, span));
        var created = constructor ? value(callable.parameters().getFirst()) : null;
        if (constructor) arguments.add(created);
        List<IrInstruction> preparation = new ArrayList<>();
        for (int index = constructor ? 1 : 0; index < callable.parameters().size(); index++) {
            int start = starts.get(index - (constructor ? 1 : 0));
            var input = parameters.get(start).value();
            if (enums.containsKey(index)) {
                arguments.add(value(callable.parameters().get(index)));
            } else if (callable.parameters().get(index).equals(STRING)) {
                var copy = value(STRING);
                copies.add(new IrBridgeStringCopyInstruction(copy, input, parameters.get(start + 1).value(), span));
                arguments.add(copy);
            } else if (callable.parameters().get(index).equals(IrType.I1)) {
                var normalized = value(IrType.I1);
                preparation.add(new IrBinaryInstruction(normalized, IrBinaryOperator.NOT_EQUAL, input,
                        new IrConstant(IrType.I8, 0, span), span));
                arguments.add(normalized);
            } else arguments.add(input);
        }
        var initialization = initialize ? new IrInvokeTerminator(
                new IrEnsureTypeInitializedInstruction(callable.owner(), span), constructor ? "allocate" : "target", "failure.before", span)
                : new IrJump(constructor ? "allocate" : "target", span);
        var enumIndices = enums.keySet().stream().sorted().toList();
        String afterCopies = enumIndices.isEmpty() ? "initialize" : "convert." + enumIndices.getFirst();
        blocks.add(new IrBasicBlock("entry", preparation, !copies.isEmpty() ? new IrJump("copy.0", span)
                : enumIndices.isEmpty() ? initialization : new IrJump(afterCopies, span), span));
        for (int index = 0; index < copies.size(); index++) {
            blocks.add(new IrBasicBlock("copy." + index, List.of(), new IrInvokeTerminator(copies.get(index),
                    index + 1 == copies.size() ? afterCopies : "copy." + (index + 1), "failure.copy." + index, span), span));
        }
        for (int position = 0; position < enumIndices.size(); position++) {
            int index = enumIndices.get(position);
            var token = parameters.get(starts.get(index - (constructor ? 1 : 0))).value();
            next = BridgeEnumConversion.append(blocks, enums.get(index), token, (IrValueReference) arguments.get(index), next,
                    "convert." + index, position + 1 == enumIndices.size() ? "initialize" : "convert." + enumIndices.get(position + 1),
                    "failure.before", "invalid", span);
        }
        if (!copies.isEmpty() || !enumIndices.isEmpty()) blocks.add(new IrBasicBlock("initialize", List.of(), initialization, span));
        if (constructor) blocks.add(new IrBasicBlock("allocate", List.of(), new IrInvokeTerminator(
                new IrAllocateInstruction(created, callable.owner(), span), "target", "failure.before", span), span));
        Optional<IrValueReference> result = callable.result().equals(IrType.VOID) ? Optional.empty() : Optional.of(value(callable.result()));
        blocks.add(new IrBasicBlock("target", List.of(), new IrInvokeTerminator(new IrCallInstruction(result,
                callable.linkage(), callable.result(), arguments, span), aliasResult() ? "cleanup.0" : "success",
                constructor ? "failure.constructed" : "failure.before", span), span));
        List<IrInstruction> success = new ArrayList<>();
        successCleanup(success, result);
        if (constructor) success.add(new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.VALUE, created, span));
        result.ifPresent(value -> success.add(new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.VALUE, value, span)));
        blocks.add(new IrBasicBlock("success", success, new IrJump("success.slots.0", span), span));
        snapshots("success.slots", false, "return.success");
        blocks.add(new IrBasicBlock("return.success", List.of(), returned(0), span));
        failure("failure.before", constructor, null, copies.size());
        if (constructor) failure("failure.constructed", true, created, copies.size());
        for (int acquired = 0; acquired < copies.size(); acquired++) failure("failure.copy." + acquired, constructor, null, acquired);
        blocks.add(new IrBasicBlock("snapshot.complete", List.of(), returned(1), span));
        var handle = value(IrType.EXCEPTION);
        var exception = value(IrType.EXCEPTION);
        blocks.add(new IrBasicBlock("snapshot.failure", List.of(new IrExceptionLandingPadInstruction(handle, exception, span),
                new IrExceptionCaughtInstruction(exception, span)), returned(2), span));
        if (!enumIndices.isEmpty()) {
            List<IrInstruction> invalid = new ArrayList<>();
            for (int index = copies.size() - 1; index >= 0; index--) invalid.add(new IrRawDeallocateInstruction(copies.get(index).result(), span));
            if (callable.result().isReference()) invalid.add(new IrBridgeResultStoreInstruction(frame,
                    IrBridgeResultStoreInstruction.Slot.VALUE, new IrNull(callable.result(), span), span));
            blocks.add(new IrBasicBlock("invalid", invalid, returned(3), span));
        }
        return new IrFunction(callable.owner(), "<bridge-entry>", symbol, IrType.I32, parameters, blocks,
                span, root.sourceFile(), IrCallableKind.METHOD);
    }

    private boolean aliasResult() {
        return stringResult.filter(result -> result.kind() == BridgeStringResultContract.Kind.INPUT_ALIAS).isPresent();
    }

    private void successCleanup(List<IrInstruction> success, Optional<IrValueReference> result) {
        for (int index = 0; index < copies.size(); index++) {
            var copy = copies.get(index);
            if (!aliasResult()) {
                success.add(new IrRawDeallocateInstruction(copy.result(), span));
                continue;
            }
            var retained = value(IrType.I1);
            String after = index + 1 == copies.size() ? "success" : "cleanup." + (index + 1);
            blocks.add(new IrBasicBlock("cleanup." + index, List.of(new IrBinaryInstruction(retained,
                    IrBinaryOperator.EQUAL, copy.result(), result.orElseThrow(), span)),
                    new IrBranch(retained, after, "release." + index, span), span));
            blocks.add(new IrBasicBlock("release." + index, List.of(new IrRawDeallocateInstruction(copy.result(), span)),
                    new IrJump(after, span), span));
        }
    }

    private void failure(String label, boolean omitConstructedRoot, IrValueReference rollback, int acquired) {
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
        for (int index = acquired - 1; index >= 0; index--) {
            failure.add(new IrRawDeallocateInstruction(copies.get(index).result(), span));
        }
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
