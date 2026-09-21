// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import java.util.function.Function;

/** Explicit SSA/label copying for typed CFG versioning; source metadata is unchanged. */
public final class IrCfgRenamer {
    private final Function<IrValueReference, IrValueReference> values;
    private final Function<String, String> labels;

    public IrCfgRenamer(Function<IrValueReference, IrValueReference> values,
                        Function<String, String> labels) {
        this.values = values;
        this.labels = labels;
    }

    private IrOperand operand(IrOperand operand) {
        return operand instanceof IrValueReference value ? values.apply(value) : operand;
    }

    public IrBasicBlock block(IrBasicBlock block) {
        return new IrBasicBlock(labels.apply(block.label()),
                block.instructions().stream().map(this::instruction).toList(),
                terminator(block.terminator()), block.sourceSpan());
    }

    public IrInstruction instruction(IrInstruction instruction) {
        return switch (instruction) {
            case IrAddSecondaryExceptionInstruction i -> new IrAddSecondaryExceptionInstruction(
                    operand(i.primary()), operand(i.secondary()), i.sourceSpan());
            case IrAllocateInstruction i -> new IrAllocateInstruction(
                    values.apply(i.result()), i.className(), i.sourceSpan());
            case IrAllocationCountInstruction i -> new IrAllocationCountInstruction(
                    values.apply(i.result()), i.sourceSpan());
            case IrArrayAllocateInstruction i -> new IrArrayAllocateInstruction(
                    values.apply(i.result()), i.elementType(), operand(i.length()), i.sourceSpan());
            case IrArrayBoundsCheckInstruction i -> new IrArrayBoundsCheckInstruction(
                    values.apply(i.result()), operand(i.array()), operand(i.index()), i.sourceSpan());
            case IrArrayLengthCheckInstruction i -> new IrArrayLengthCheckInstruction(
                    values.apply(i.result()), operand(i.length()), i.sourceSpan());
            case IrArrayLengthInstruction i -> new IrArrayLengthInstruction(
                    values.apply(i.result()), operand(i.array()), i.sourceSpan());
            case IrArrayLoadInstruction i -> new IrArrayLoadInstruction(
                    values.apply(i.result()), operand(i.array()), operand(i.index()), i.sourceSpan());
            case IrArrayStoreInstruction i -> new IrArrayStoreInstruction(
                    operand(i.array()), operand(i.index()), operand(i.value()), i.sourceSpan());
            case IrArrayTypeTestInstruction i -> new IrArrayTypeTestInstruction(
                    values.apply(i.result()), operand(i.value()), i.targetType(), i.sourceSpan());
            case IrBinaryInstruction i -> new IrBinaryInstruction(
                    values.apply(i.result()), i.operator(), operand(i.left()), operand(i.right()), i.sourceSpan());
            case IrCallInstruction i -> new IrCallInstruction(
                    i.result().map(values), i.targetLinkageName(), i.returnType(),
                    i.arguments().stream().map(this::operand).toList(), i.callKind(),
                    i.devirtualizedFrom(), i.specializationArguments(), i.sourceSpan());
            case IrCharacterInstruction i -> new IrCharacterInstruction(
                    values.apply(i.result()), i.operation(), operand(i.codePoint()), i.sourceSpan());
            case IrDestroyArrayElementsInstruction i -> new IrDestroyArrayElementsInstruction(
                    operand(i.array()), i.sourceSpan());
            case IrEnsureTypeInitializedInstruction i -> new IrEnsureTypeInitializedInstruction(
                    i.typeName(), i.sourceSpan());
            case IrExceptionCaughtInstruction i -> new IrExceptionCaughtInstruction(
                    operand(i.exception()), i.sourceSpan());
            case IrExceptionLandingPadInstruction i -> new IrExceptionLandingPadInstruction(
                    values.apply(i.exceptionHandle()), values.apply(i.exceptionObject()), i.sourceSpan());
            case IrFieldLoadInstruction i -> new IrFieldLoadInstruction(
                    values.apply(i.result()), operand(i.receiver()), i.field(), i.sourceSpan());
            case IrFieldStoreInstruction i -> new IrFieldStoreInstruction(
                    operand(i.receiver()), i.field(), operand(i.value()), i.sourceSpan());
            case IrFileInstruction i -> new IrFileInstruction(
                    values.apply(i.result()), i.operation(), i.path().map(this::operand), i.value().map(this::operand), i.sourceSpan());
            case IrFloatingBitsInstruction i -> new IrFloatingBitsInstruction(
                    values.apply(i.result()), i.operation(), operand(i.value()), i.sourceSpan());
            case IrFloatingParseInstruction i -> new IrFloatingParseInstruction(
                    values.apply(i.result()), operand(i.text()), i.sourceSpan());
            case IrFreeInstruction i -> new IrFreeInstruction(
                    operand(i.allocation()), i.sourceSpan());
            case IrIdentityHashCodeInstruction i -> new IrIdentityHashCodeInstruction(
                    values.apply(i.result()), operand(i.object()), i.sourceSpan());
            case IrInstanceOfInstruction i -> new IrInstanceOfInstruction(
                    values.apply(i.result()), operand(i.value()), i.targetTypeName(), i.targetTypeId(), i.exactTargetType(), i.sourceSpan());
            case IrInterfaceCallInstruction i -> new IrInterfaceCallInstruction(
                    i.result().map(values), i.interfaceName(), i.slot(), i.returnType(),
                    i.arguments().stream().map(this::operand).toList(), i.specializationArguments(), i.sourceSpan());
            case IrLiveAllocationCountInstruction i -> new IrLiveAllocationCountInstruction(
                    values.apply(i.result()), i.sourceSpan());
            case IrMathBinaryInstruction i -> new IrMathBinaryInstruction(
                    values.apply(i.result()), i.operation(), operand(i.left()), operand(i.right()), i.sourceSpan());
            case IrMathUnaryInstruction i -> new IrMathUnaryInstruction(
                    values.apply(i.result()), i.operation(), operand(i.value()), i.sourceSpan());
            case IrNullCheckInstruction i -> new IrNullCheckInstruction(
                    values.apply(i.result()), operand(i.receiver()), i.sourceSpan());
            case IrNumericConversionInstruction i -> new IrNumericConversionInstruction(
                    values.apply(i.result()), operand(i.value()), i.sourceSpan());
            case IrObjectHashCodeInstruction i -> new IrObjectHashCodeInstruction(
                    values.apply(i.result()), operand(i.object()), i.sourceSpan());
            case IrObjectToStringInstruction i -> new IrObjectToStringInstruction(
                    values.apply(i.result()), operand(i.object()), i.sourceSpan());
            case IrPhiInstruction i -> new IrPhiInstruction(
                    values.apply(i.result()), i.incoming().stream().map(p ->
                    new IrPhiIncoming(labels.apply(p.predecessor()), operand(p.value()))).toList(), i.sourceSpan());
            case IrPrintStreamCheckErrorInstruction i -> new IrPrintStreamCheckErrorInstruction(
                    values.apply(i.result()), operand(i.stream()), i.sourceSpan());
            case IrPrintStreamFlushInstruction i -> new IrPrintStreamFlushInstruction(
                    operand(i.stream()), i.sourceSpan());
            case IrPrintStreamPrintlnInstruction i -> new IrPrintStreamPrintlnInstruction(
                    operand(i.value()), i.sourceSpan());
            case IrPrintStreamWriteInstruction i -> new IrPrintStreamWriteInstruction(
                    operand(i.stream()), i.value().map(p -> new IrStringConcatPart(p.kind(), operand(p.value()))), i.newline(), i.sourceSpan());
            case IrRawDeallocateInstruction i -> new IrRawDeallocateInstruction(
                    operand(i.allocation()), i.sourceSpan());
            case IrReferenceConversionInstruction i -> new IrReferenceConversionInstruction(
                    values.apply(i.result()), operand(i.value()), i.sourceSpan());
            case IrReleaseOwnedThrowableMessageInstruction i -> new IrReleaseOwnedThrowableMessageInstruction(
                    operand(i.throwable()), operand(i.message()), i.sourceSpan());
            case IrReleaseOwnedToStringResultInstruction i -> new IrReleaseOwnedToStringResultInstruction(
                    operand(i.object()), operand(i.result()), i.sourceSpan());
            case IrRollbackInstruction i -> new IrRollbackInstruction(
                    operand(i.allocation()), i.sourceSpan());
            case IrSecondaryExceptionAtInstruction i -> new IrSecondaryExceptionAtInstruction(
                    values.apply(i.result()), operand(i.primary()), operand(i.index()), i.sourceSpan());
            case IrSecondaryExceptionCountInstruction i -> new IrSecondaryExceptionCountInstruction(
                    values.apply(i.result()), operand(i.primary()), i.sourceSpan());
            case IrStaticFieldLoadInstruction i -> new IrStaticFieldLoadInstruction(
                    values.apply(i.result()), i.field(), i.sourceSpan());
            case IrStaticFieldStoreInstruction i -> new IrStaticFieldStoreInstruction(
                    i.field(), operand(i.value()), i.sourceSpan());
            case IrStreamInstruction i -> new IrStreamInstruction(
                    values.apply(i.result()), i.operation(), i.arguments().stream().map(this::operand).toList(), i.sourceSpan());
            case IrStringCaseInstruction i -> new IrStringCaseInstruction(
                    values.apply(i.result()), operand(i.source()), operand(i.upper()), i.sourceSpan());
            case IrStringCharAtInstruction i -> new IrStringCharAtInstruction(
                    values.apply(i.result()), operand(i.string()), operand(i.index()), i.sourceSpan());
            case IrStringConcatInstruction i -> new IrStringConcatInstruction(
                    values.apply(i.result()), i.parts().stream().map(p ->
                    new IrStringConcatPart(p.kind(), operand(p.value()))).toList(), i.sourceSpan());
            case IrStringCopyInstruction i -> new IrStringCopyInstruction(
                    values.apply(i.result()), operand(i.source()), i.sourceSpan());
            case IrStringEqualsIgnoreCaseInstruction i -> new IrStringEqualsIgnoreCaseInstruction(
                    values.apply(i.result()), operand(i.source()), operand(i.other()), i.sourceSpan());
            case IrStringEqualsInstruction i -> new IrStringEqualsInstruction(
                    values.apply(i.result()), operand(i.string()), operand(i.other()), i.sourceSpan());
            case IrStringFromCharRangeInstruction i -> new IrStringFromCharRangeInstruction(
                    values.apply(i.result()), operand(i.characters()), operand(i.offset()), operand(i.length()), i.sourceSpan());
            case IrStringFromCharacterInstruction i -> new IrStringFromCharacterInstruction(
                    values.apply(i.result()), operand(i.value()), i.sourceSpan());
            case IrStringFromCharsInstruction i -> new IrStringFromCharsInstruction(
                    values.apply(i.result()), operand(i.characters()), operand(i.length()), i.sourceSpan());
            case IrStringFromIntegerInstruction i -> new IrStringFromIntegerInstruction(
                    values.apply(i.result()), operand(i.value()), operand(i.radix()), i.sourceSpan());
            case IrStringFromRangeInstruction i -> new IrStringFromRangeInstruction(
                    values.apply(i.result()), operand(i.source()), operand(i.beginIndex()), operand(i.length()), i.sourceSpan());
            case IrStringFromUtf8Instruction i -> new IrStringFromUtf8Instruction(
                    values.apply(i.result()), operand(i.bytes()), operand(i.length()), i.sourceSpan());
            case IrStringHashCodeInstruction i -> new IrStringHashCodeInstruction(
                    values.apply(i.result()), operand(i.string()), i.sourceSpan());
            case IrStringJoinInstruction i -> new IrStringJoinInstruction(
                    values.apply(i.result()), operand(i.delimiter()), operand(i.elements()), i.sourceSpan());
            case IrStringRepeatInstruction i -> new IrStringRepeatInstruction(
                    values.apply(i.result()), operand(i.source()), operand(i.count()), i.sourceSpan());
            case IrStringReplaceCharInstruction i -> new IrStringReplaceCharInstruction(
                    values.apply(i.result()), operand(i.source()), operand(i.oldChar()), operand(i.newChar()), i.sourceSpan());
            case IrStringReplaceTextInstruction i -> new IrStringReplaceTextInstruction(
                    values.apply(i.result()), operand(i.source()), operand(i.target()), operand(i.replacement()), i.sourceSpan());
            case IrSystemArrayCopyInstruction i -> new IrSystemArrayCopyInstruction(
                    operand(i.source()), operand(i.sourcePosition()), operand(i.destination()),
                    operand(i.destinationPosition()), operand(i.length()), i.sourceSpan());
            case IrSystemClockInstruction i -> new IrSystemClockInstruction(
                    values.apply(i.result()), i.clock(), i.sourceSpan());
            case IrSystemExitInstruction i -> new IrSystemExitInstruction(
                    operand(i.status()), i.sourceSpan());
            case IrSystemGetenvInstruction i -> new IrSystemGetenvInstruction(
                    values.apply(i.result()), operand(i.name()), i.sourceSpan());
            case IrSystemPropertyInstruction i -> new IrSystemPropertyInstruction(
                    values.apply(i.result()), operand(i.name()), i.sourceSpan());
            case IrTcpInstruction i -> new IrTcpInstruction(
                    values.apply(i.result()), i.operation(), i.arguments().stream().map(this::operand).toList(), i.outputFields(), i.sourceSpan());
            case IrThrowableDescriptionInstruction i -> new IrThrowableDescriptionInstruction(
                    values.apply(i.result()), operand(i.throwable()), operand(i.message()), i.sourceSpan());
            case IrThrowableTraceInstruction i -> new IrThrowableTraceInstruction(
                    i.result().map(values), i.operation(), i.arguments().stream().map(this::operand).toList(), i.sourceSpan());
            case IrTlsInstruction i -> new IrTlsInstruction(
                    values.apply(i.result()), i.operation(), i.arguments().stream().map(this::operand).toList(), i.sourceSpan());
            case IrTypeInitializedInstruction i -> new IrTypeInitializedInstruction(
                    values.apply(i.result()), i.typeName(), i.sourceSpan());
            case IrUnaryInstruction i -> new IrUnaryInstruction(
                    values.apply(i.result()), i.operator(), operand(i.operand()), i.sourceSpan());
            case IrVirtualCallInstruction i -> new IrVirtualCallInstruction(
                    i.result().map(values), i.slot(), i.returnType(), i.arguments().stream().map(this::operand).toList(),
                    i.specializationArguments(), i.sourceSpan());
        };
    }

    public IrTerminator terminator(IrTerminator terminator) {
        return switch (terminator) {
            case IrBranch t -> new IrBranch(operand(t.condition()), labels.apply(t.trueTarget()),
                    labels.apply(t.falseTarget()), t.sourceSpan());
            case IrJump t -> new IrJump(labels.apply(t.target()), t.sourceSpan());
            case IrInvokeTerminator t -> new IrInvokeTerminator(instruction(t.call()),
                    labels.apply(t.normalTarget()), labels.apply(t.unwindTarget()), t.sourceSpan());
            case IrReturnTerminator t -> new IrReturnTerminator(t.value().map(this::operand), t.sourceSpan());
            case IrSwitchTerminator t -> new IrSwitchTerminator(operand(t.selector()), t.cases().stream()
                    .map(c -> new IrSwitchCase(c.value(), labels.apply(c.target()), c.sourceSpan())).toList(),
                    labels.apply(t.defaultTarget()), t.sourceSpan());
            case IrThrowTerminator t -> new IrThrowTerminator(operand(t.exception()),
                    labels.apply(t.normalTarget()), t.unwindTarget().map(labels), t.sourceSpan());
            case IrUnreachable t -> t;
        };
    }
}
