// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Protected native active-use conversion, using only named public enum fields. */
final class BridgeEnumEntryLowering {
    private BridgeEnumEntryLowering() {}

    static IrFunction lower(BridgeRootSet.Root root, String symbol, BridgeEnumInputs proof,
                            boolean initialize, boolean isStatic) {
        var span = root.span();
        var callable = root.callable();
        List<IrParameter> parameters = new ArrayList<>();
        for (var type : callable.parameters()) {
            int id = parameters.size();
            var carrier = type.isReference() ? IrType.I32 : type.equals(IrType.I1) ? IrType.I8 : type;
            parameters.add(new IrParameter("argument" + id, new IrValueReference(id, carrier, span), span));
        }
        var frame = new IrValueReference(parameters.size(), IrType.I64, span);
        parameters.add(new IrParameter("resultFrame", frame, span));
        int next = parameters.size();
        List<IrBasicBlock> blocks = new ArrayList<>();
        List<IrInstruction> preparation = new ArrayList<>();
        List<IrOperand> arguments = new ArrayList<>();
        List<Integer> references = new ArrayList<>();
        for (int index = 0; index < callable.parameters().size(); index++) {
            var type = callable.parameters().get(index);
            var input = parameters.get(index).value();
            if (type.isReference()) {
                references.add(index);
                arguments.add(new IrValueReference(next++, type, span));
            } else if (type.equals(IrType.I1)) {
                var normalized = new IrValueReference(next++, IrType.I1, span);
                preparation.add(new IrBinaryInstruction(normalized, IrBinaryOperator.NOT_EQUAL, input,
                        new IrConstant(IrType.I8, 0, span), span));
                arguments.add(normalized);
            } else arguments.add(input);
        }
        blocks.add(new IrBasicBlock("entry", preparation, new IrJump(references.isEmpty()
                ? "initialize" : "convert." + references.getFirst(), span), span));
        for (int position = 0; position < references.size(); position++) {
            int index = references.get(position);
            String label = "convert." + index;
            var type = callable.parameters().get(index);
            var input = parameters.get(index).value();
            var parameter = new BridgeEnumInvocation.Parameter(index, type, proof.constants().get(type), isStatic || index != 0);
            next = BridgeEnumConversion.append(blocks, parameter, input, (IrValueReference) arguments.get(index), next,
                    label, position + 1 == references.size() ? "initialize" : "convert." + references.get(position + 1),
                    "failure", "invalid", span);
        }
        blocks.add(new IrBasicBlock("initialize", List.of(), initialize ? new IrInvokeTerminator(
                new IrEnsureTypeInitializedInstruction(callable.owner(), span), "target", "failure", span)
                : new IrJump("target", span), span));
        Optional<IrValueReference> result = callable.result().equals(IrType.VOID) ? Optional.empty()
                : Optional.of(new IrValueReference(next++, callable.result(), span));
        blocks.add(new IrBasicBlock("target", List.of(), new IrInvokeTerminator(new IrCallInstruction(result,
                callable.linkage(), callable.result(), arguments, span), "success", "failure", span), span));
        blocks.add(new IrBasicBlock("success", result.<List<IrInstruction>>map(value -> List.of(
                new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.VALUE, value, span)))
                .orElse(List.of()), new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 0, span)), span), span));
        var handle = new IrValueReference(next++, IrType.EXCEPTION, span);
        var exception = new IrValueReference(next++, IrType.EXCEPTION, span);
        blocks.add(new IrBasicBlock("failure", List.of(new IrExceptionLandingPadInstruction(handle, exception, span),
                new IrExceptionCaughtInstruction(exception, span), new IrBridgeResultStoreInstruction(frame,
                IrBridgeResultStoreInstruction.Slot.EXCEPTION, exception, span)), new IrInvokeTerminator(
                new IrBridgeFailureSnapshotInstruction(exception, frame, span), "snapshot.complete", "snapshot.failure", span), span));
        blocks.add(new IrBasicBlock("snapshot.complete", List.of(),
                new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 1, span)), span), span));
        var snapshotHandle = new IrValueReference(next++, IrType.EXCEPTION, span);
        var snapshotException = new IrValueReference(next, IrType.EXCEPTION, span);
        blocks.add(new IrBasicBlock("snapshot.failure", List.of(new IrExceptionLandingPadInstruction(snapshotHandle, snapshotException, span),
                new IrExceptionCaughtInstruction(snapshotException, span)),
                new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 2, span)), span), span));
        // Adapter generation validates tokens; an invalid private ABI input never reaches source code.
        blocks.add(new IrBasicBlock("invalid", List.of(),
                new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 3, span)), span), span));
        return new IrFunction(callable.owner(), "<bridge-entry>", symbol, IrType.I32, parameters, blocks,
                span, root.sourceFile(), IrCallableKind.METHOD);
    }
}
