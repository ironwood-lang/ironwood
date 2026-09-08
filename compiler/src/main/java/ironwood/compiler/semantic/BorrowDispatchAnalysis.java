// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrThrowableTraceInstruction;
import ironwood.compiler.ir.IrAddSecondaryExceptionInstruction;
import ironwood.compiler.ir.IrAllocateInstruction;
import ironwood.compiler.ir.IrAllocationCountInstruction;
import ironwood.compiler.ir.IrArrayAllocateInstruction;
import ironwood.compiler.ir.IrArrayBoundsCheckInstruction;
import ironwood.compiler.ir.IrArrayLengthCheckInstruction;
import ironwood.compiler.ir.IrArrayLengthInstruction;
import ironwood.compiler.ir.IrArrayLoadInstruction;
import ironwood.compiler.ir.IrArrayStoreInstruction;
import ironwood.compiler.ir.IrArrayTypeTestInstruction;
import ironwood.compiler.ir.IrBasicBlock;
import ironwood.compiler.ir.IrBinaryInstruction;
import ironwood.compiler.ir.IrCallInstruction;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrConstant;
import ironwood.compiler.ir.IrDispatchSlot;
import ironwood.compiler.ir.IrEnsureTypeInitializedInstruction;
import ironwood.compiler.ir.IrEnumConstant;
import ironwood.compiler.ir.IrExceptionCaughtInstruction;
import ironwood.compiler.ir.IrExceptionLandingPadInstruction;
import ironwood.compiler.ir.IrFieldLoadInstruction;
import ironwood.compiler.ir.IrFieldStoreInstruction;
import ironwood.compiler.ir.IrFileInstruction;
import ironwood.compiler.ir.IrFloatingParseInstruction;
import ironwood.compiler.ir.IrFreeInstruction;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrIdentityHashCodeInstruction;
import ironwood.compiler.ir.IrImmortalObject;
import ironwood.compiler.ir.IrInstanceOfInstruction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrInterfaceCallInstruction;
import ironwood.compiler.ir.IrInvokeTerminator;
import ironwood.compiler.ir.IrLiveAllocationCountInstruction;
import ironwood.compiler.ir.IrMathBinaryInstruction;
import ironwood.compiler.ir.IrMathUnaryInstruction;
import ironwood.compiler.ir.IrNull;
import ironwood.compiler.ir.IrNullCheckInstruction;
import ironwood.compiler.ir.IrNumericConversionInstruction;
import ironwood.compiler.ir.IrObjectHashCodeInstruction;
import ironwood.compiler.ir.IrObjectToStringInstruction;
import ironwood.compiler.ir.IrThrowableDescriptionInstruction;
import ironwood.compiler.ir.IrReleaseOwnedThrowableMessageInstruction;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrParameter;
import ironwood.compiler.ir.IrPhiInstruction;
import ironwood.compiler.ir.IrPrintStreamCheckErrorInstruction;
import ironwood.compiler.ir.IrPrintStreamFlushInstruction;
import ironwood.compiler.ir.IrPrintStreamPrintlnInstruction;
import ironwood.compiler.ir.IrPrintStreamWriteInstruction;
import ironwood.compiler.ir.IrRawDeallocateInstruction;
import ironwood.compiler.ir.IrReferenceConversionInstruction;
import ironwood.compiler.ir.IrReleaseOwnedToStringResultInstruction;
import ironwood.compiler.ir.IrReturnTerminator;
import ironwood.compiler.ir.IrRollbackInstruction;
import ironwood.compiler.ir.IrSecondaryExceptionAtInstruction;
import ironwood.compiler.ir.IrSecondaryExceptionCountInstruction;
import ironwood.compiler.ir.IrStaticField;
import ironwood.compiler.ir.IrStaticFieldLoadInstruction;
import ironwood.compiler.ir.IrStaticFieldStoreInstruction;
import ironwood.compiler.ir.IrStreamInstruction;
import ironwood.compiler.ir.IrStringCharAtInstruction;
import ironwood.compiler.ir.IrStringConcatInstruction;
import ironwood.compiler.ir.IrStringConstant;
import ironwood.compiler.ir.IrStringCopyInstruction;
import ironwood.compiler.ir.IrStringEqualsInstruction;
import ironwood.compiler.ir.IrStringFromCharRangeInstruction;
import ironwood.compiler.ir.IrStringFromUtf8Instruction;
import ironwood.compiler.ir.IrStringJoinInstruction;
import ironwood.compiler.ir.IrStringEqualsIgnoreCaseInstruction;
import ironwood.compiler.ir.IrStringReplaceTextInstruction;
import ironwood.compiler.ir.IrStringReplaceCharInstruction;
import ironwood.compiler.ir.IrStringRepeatInstruction;
import ironwood.compiler.ir.IrStringCaseInstruction;
import ironwood.compiler.ir.IrStringFromCharacterInstruction;
import ironwood.compiler.ir.IrStringFromCharsInstruction;
import ironwood.compiler.ir.IrStringFromIntegerInstruction;
import ironwood.compiler.ir.IrStringFromRangeInstruction;
import ironwood.compiler.ir.IrStringHashCodeInstruction;
import ironwood.compiler.ir.IrSystemArrayCopyInstruction;
import ironwood.compiler.ir.IrSystemClockInstruction;
import ironwood.compiler.ir.IrSystemGetenvInstruction;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrUnaryInstruction;
import ironwood.compiler.ir.IrValueReference;
import ironwood.compiler.ir.IrVirtualCallInstruction;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Call-site targets for the non-retaining primitive/void-call proof. The input is
 * provisional typed IR, so overload selection and default-method resolution use
 * the same bindings as executable code. This analysis never grants ownership.
 *
 * Reference flow is a monotone, context-insensitive union. Fields are joined by
 * declaration across all instances; every body and exceptional edge contributes.
 * Array elements and native reference results are unknown within their type.
 */
final class BorrowDispatchAnalysis {
    private record Value(String function, IrValueReference value) {}
    private record Field(String owner, String name) {}
    private record CallSite(String function, SourceSpan span, String name) {}
    private record Dispatch(String receiver, String slot) {}
    private record Operation(IrFunction function, IrInstruction instruction) {}
    private record Call(Optional<IrValueReference> result, List<IrOperand> arguments,
                        String directTarget, IrDispatchSlot slot) {}

    private final Map<String, TypeSymbol> types;
    private final ClassHierarchy hierarchy;
    private final Map<String, IrFunction> functions = new LinkedHashMap<>();
    private final Map<Value, Set<String>> values = new LinkedHashMap<>();
    private final Map<Field, Set<String>> fields = new LinkedHashMap<>();
    private final Map<String, Set<String>> returns = new LinkedHashMap<>();
    private final Map<IrType, Set<String>> unknownTypes = new LinkedHashMap<>();
    private final Map<Dispatch, Optional<String>> implementations = new LinkedHashMap<>();
    private final Map<CallSite, Set<String>> primitiveTargets = new LinkedHashMap<>();
    private final List<Operation> operations = new ArrayList<>();
    private boolean changed;

    BorrowDispatchAnalysis(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                           List<IrFunction> input, List<IrStaticField> staticFields,
                           boolean hasEntryPoint) {
        this.types = types;
        this.hierarchy = hierarchy;
        for (IrFunction function : input) {
            functions.put(function.linkageName(), function);
            for (IrBasicBlock block : function.blocks()) {
                block.instructions().forEach(instruction ->
                        operations.add(new Operation(function, instruction)));
                if (block.terminator() instanceof IrInvokeTerminator invoke) {
                    operations.add(new Operation(function, invoke.call()));
                }
            }
        }

        // Without a selected entry point, library arguments are unknown. In a
        // closed-world executable, ordinary reference arguments originate in IR
        // calls (main receives only String[]; array loads stay conservative).
        // Destructors are implicit native callbacks, so seed their receivers
        // even when no ordinary invocation is present. Other source methods and
        // constructors receive this through the same call edges as arguments.
        for (IrFunction function : input) {
            for (IrParameter parameter : function.parameters()) {
                if (!hasEntryPoint || parameter.name().equals("this")
                        && (function.kind() == IrCallableKind.DESTRUCTOR
                        || function.kind() == IrCallableKind.CONSTRUCTOR_ROLLBACK)) {
                    addValue(function, parameter.value(), unknown(parameter.value().type()));
                }
            }
        }
        for (IrStaticField field : staticFields) {
            merge(fields, new Field(field.ownerClass(), field.name()),
                    constantTypes(field.initialValue()));
        }
        do {
            changed = false;
            for (Operation operation : operations) {
                propagate(operation.function(), operation.instruction());
            }
            for (IrFunction function : input) {
                for (IrBasicBlock block : function.blocks()) {
                    if (block.terminator() instanceof IrReturnTerminator returned) {
                        returned.value().ifPresent(value -> merge(returns,
                                function.linkageName(), valueTypes(function, value)));
                    }
                }
            }
        } while (changed);

        for (Operation operation : operations) {
            Call call = call(operation.instruction());
            if (call == null || call.result().filter(value -> value.type().isReference()).isPresent()) {
                continue;
            }
            String name = call.directTarget() == null ? call.slot().methodName()
                    : Optional.ofNullable(functions.get(call.directTarget()))
                    .map(IrFunction::sourceName).orElse("");
            Set<String> targets = targets(operation.function(), call, false);
            // Empty flow is not a proof: check all type-compatible targets. This
            // also keeps checks meaningful in bodies that have no observed caller.
            if (targets.isEmpty()) {
                targets = targets(operation.function(), call, true);
            }
            primitiveTargets.computeIfAbsent(new CallSite(operation.function().linkageName(),
                    operation.instruction().sourceSpan(), name), ignored -> new LinkedHashSet<>())
                    .addAll(targets);
        }
    }

    Set<String> primitiveTargets(String caller, SourceSpan span, String name) {
        return primitiveTargets.getOrDefault(new CallSite(caller, span, name), Set.of());
    }

    private void propagate(IrFunction function, IrInstruction instruction) {
        switch (instruction) {
            case IrAllocateInstruction allocate ->
                    addValue(function, allocate.result(), Set.of(allocate.className()));
            case IrReferenceConversionInstruction conversion -> addValue(function,
                    conversion.result(), convertedTypes(function, conversion));
            case IrPhiInstruction phi -> phi.incoming().forEach(incoming ->
                    addValue(function, phi.result(), valueTypes(function, incoming.value())));
            case IrFieldStoreInstruction store -> merge(fields,
                    new Field(store.field().ownerClass(), store.field().name()),
                    valueTypes(function, store.value()));
            case IrStaticFieldStoreInstruction store -> merge(fields,
                    new Field(store.field().ownerClass(), store.field().name()),
                    valueTypes(function, store.value()));
            case IrFieldLoadInstruction load -> addValue(function, load.result(),
                    fields.getOrDefault(new Field(load.field().ownerClass(), load.field().name()), Set.of()));
            case IrStaticFieldLoadInstruction load -> addValue(function, load.result(),
                    fields.getOrDefault(new Field(load.field().ownerClass(), load.field().name()), Set.of()));
            case IrCallInstruction ignored -> propagateCall(function, call(instruction));
            case IrVirtualCallInstruction ignored -> propagateCall(function, call(instruction));
            case IrInterfaceCallInstruction ignored -> propagateCall(function, call(instruction));
            // These reference producers do not expose a source-level value-flow
            // contract. Include every compatible concrete type, never an empty set.
            case IrArrayLoadInstruction load -> unknownResult(function, load.result());
            case IrExceptionLandingPadInstruction landing -> unknownResult(function, landing.exceptionObject());
            case IrSecondaryExceptionAtInstruction secondary -> unknownResult(function, secondary.result());
            case IrFileInstruction file -> unknownResult(function, file.result());
            case IrObjectToStringInstruction string -> unknownResult(function, string.result());
            case IrThrowableDescriptionInstruction string -> unknownResult(function, string.result());
            case IrStringConcatInstruction string -> unknownResult(function, string.result());
            case IrStringCopyInstruction string -> unknownResult(function, string.result());
            case IrStringFromCharsInstruction string -> unknownResult(function, string.result());
            case IrStringRepeatInstruction string -> unknownResult(function, string.result());
            case IrStringCaseInstruction string -> unknownResult(function, string.result());
            case IrStringReplaceCharInstruction string -> unknownResult(function, string.result());
            case IrStringReplaceTextInstruction string -> unknownResult(function, string.result());
            case IrStringEqualsIgnoreCaseInstruction string -> unknownResult(function, string.result());
            case IrStringJoinInstruction string -> unknownResult(function, string.result());
            case IrStringFromUtf8Instruction string -> unknownResult(function, string.result());
            case IrStringFromCharRangeInstruction string -> unknownResult(function, string.result());
            case IrStringFromRangeInstruction string -> unknownResult(function, string.result());
            case IrStringFromIntegerInstruction string -> unknownResult(function, string.result());
            case IrStringFromCharacterInstruction string -> unknownResult(function, string.result());
            case IrSystemGetenvInstruction string -> unknownResult(function, string.result());
            // Exhaustive list: adding a new IR operation requires reviewing its
            // reference flow instead of silently treating a new result as null.
            case IrAddSecondaryExceptionInstruction ignored -> { }
            case IrAllocationCountInstruction ignored -> { }
            case IrLiveAllocationCountInstruction ignored -> { }
            case IrArrayAllocateInstruction ignored -> { }
            case IrArrayBoundsCheckInstruction ignored -> { }
            case IrArrayLengthCheckInstruction ignored -> { }
            case IrArrayLengthInstruction ignored -> { }
            case IrArrayStoreInstruction ignored -> { }
            case IrArrayTypeTestInstruction ignored -> { }
            case IrBinaryInstruction ignored -> { }
            case IrPrintStreamPrintlnInstruction ignored -> { }
            case IrPrintStreamWriteInstruction ignored -> { }
            case IrPrintStreamFlushInstruction ignored -> { }
            case IrPrintStreamCheckErrorInstruction ignored -> { }
            case IrExceptionCaughtInstruction ignored -> { }
            case IrFreeInstruction ignored -> { }
            case ironwood.compiler.ir.IrDestroyArrayElementsInstruction ignored -> { }
            case IrInstanceOfInstruction ignored -> { }
            case IrStreamInstruction ignored -> { }
            case ironwood.compiler.ir.IrCharacterInstruction ignored -> { }
            case ironwood.compiler.ir.IrFloatingBitsInstruction ignored -> { }
            case IrFloatingParseInstruction ignored -> { }
            case IrRawDeallocateInstruction ignored -> { }
            case IrReleaseOwnedToStringResultInstruction ignored -> { }
            case IrReleaseOwnedThrowableMessageInstruction ignored -> { }
            case IrRollbackInstruction ignored -> { }
            case IrIdentityHashCodeInstruction ignored -> { }
            case IrNullCheckInstruction ignored -> { }
            case IrEnsureTypeInitializedInstruction ignored -> { }
            case IrObjectHashCodeInstruction ignored -> { }
            case IrStringCharAtInstruction ignored -> { }
            case IrStringEqualsInstruction ignored -> { }
            case IrStringHashCodeInstruction ignored -> { }
            case IrMathUnaryInstruction ignored -> { }
            case IrMathBinaryInstruction ignored -> { }
            case IrNumericConversionInstruction ignored -> { }
            case IrUnaryInstruction ignored -> { }
            case IrSecondaryExceptionCountInstruction ignored -> { }
            case IrThrowableTraceInstruction ignored -> { }
            case IrSystemArrayCopyInstruction ignored -> { }
            case IrSystemClockInstruction ignored -> { }
            case ironwood.compiler.ir.IrSystemExitInstruction ignored -> { }
            case ironwood.compiler.ir.IrSystemPropertyInstruction ignored -> { }
        }
    }

    private void propagateCall(IrFunction caller, Call call) {
        for (String target : targets(caller, call, false)) {
            IrFunction callee = functions.get(target);
            if (callee == null) {
                call.result().ifPresent(result -> unknownResult(caller, result));
                continue;
            }
            for (int index = 0; index < callee.parameters().size(); index++) {
                IrValueReference parameter = callee.parameters().get(index).value();
                addValue(callee, parameter, index < call.arguments().size()
                        ? valueTypes(caller, call.arguments().get(index)) : unknown(parameter.type()));
            }
            call.result().ifPresent(result -> addValue(caller, result,
                    returns.getOrDefault(target, Set.of())));
        }
    }

    private static Call call(IrInstruction instruction) {
        if (instruction instanceof IrCallInstruction call) {
            return new Call(call.result(), call.arguments(), call.targetLinkageName(), null);
        }
        if (instruction instanceof IrVirtualCallInstruction call) {
            return new Call(call.result(), call.arguments(), null, call.slot());
        }
        if (instruction instanceof IrInterfaceCallInstruction call) {
            return new Call(call.result(), call.arguments(), null, call.slot());
        }
        return null;
    }

    private Set<String> targets(IrFunction caller, Call call, boolean unknownReceiver) {
        if (call.directTarget() != null) { return Set.of(call.directTarget()); }
        Set<String> targets = new LinkedHashSet<>();
        IrOperand receiver = call.arguments().getFirst();
        Set<String> receivers = unknownReceiver ? unknown(receiver.type()) : valueTypes(caller, receiver);
        for (String concrete : receivers) {
            implementations.computeIfAbsent(new Dispatch(concrete, call.slot().key()), key ->
                    hierarchy.resolveDispatchImplementation(types.get(key.receiver()), key.slot())
                            .map(CallableSymbol::linkageName)).ifPresent(targets::add);
        }
        return targets;
    }

    private Set<String> unknown(IrType type) {
        return unknownTypes.computeIfAbsent(type, ignored -> {
            // Landing pads represent the language exception object with the
            // internal EXCEPTION type before converting it to a catch type.
            IrType erased = type.equals(IrType.EXCEPTION)
                    ? IrType.reference("ironwood.lang.Throwable") : type.erasure();
            if (!erased.isNominalReference()) { return Set.of(); }
            Set<String> result = new LinkedHashSet<>();
            for (TypeSymbol concrete : hierarchy.concreteSubtypes(erased.referenceName())) {
                result.add(concrete.name());
            }
            return result;
        });
    }

    private Set<String> valueTypes(IrFunction function, IrOperand operand) {
        return operand instanceof IrValueReference value
                ? values.getOrDefault(new Value(function.linkageName(), value), Set.of())
                : constantTypes(operand);
    }

    private Set<String> convertedTypes(IrFunction function, IrReferenceConversionInstruction conversion) {
        Set<String> result = new LinkedHashSet<>(valueTypes(function, conversion.value()));
        IrType target = conversion.result().type().erasure();
        if (target.isNominalReference()) {
            // Conversions follow type checks, including catch matching. A value
            // incompatible with the result type cannot reach the following call.
            result.removeIf(type -> !hierarchy.isSubtype(type, target.referenceName()));
        }
        return result;
    }

    private Set<String> constantTypes(IrOperand operand) {
        return switch (operand) {
            case IrImmortalObject object -> Set.of(object.storageType().referenceName());
            case IrEnumConstant constant -> Set.of(constant.storageType().referenceName());
            case IrStringConstant ignored -> Set.of("ironwood.lang.String");
            case IrNull ignored -> Set.of();
            case IrConstant ignored -> Set.of();
            case IrValueReference value -> unknown(value.type());
        };
    }

    private void unknownResult(IrFunction function, IrValueReference result) {
        addValue(function, result, unknown(result.type()));
    }

    private void addValue(IrFunction function, IrValueReference value, Set<String> incoming) {
        if (value.type().isReference() || value.type().equals(IrType.EXCEPTION)) {
            merge(values, new Value(function.linkageName(), value), incoming);
        }
    }

    private <K> void merge(Map<K, Set<String>> destination, K key, Set<String> incoming) {
        if (!incoming.isEmpty()) {
            changed |= destination.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(incoming);
        }
    }
}
