// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrStreamInstruction;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Materializes allocation-free primitive generic shapes in compiler-owned typed IR. */
final class PrimitiveGenericSpecializer {
    private final Map<String, TypeSymbol> types;
    private final ClassHierarchy hierarchy;
    private final List<Diagnostic> diagnostics;
    private final Map<String, IrFunction> originalFunctions = new LinkedHashMap<>();
    private final Map<String, CallableSymbol> callables = new LinkedHashMap<>();
    private final Map<String, IrClass> originalClasses = new LinkedHashMap<>();
    private final Map<IrStaticField, IrStaticField> staticFields = new LinkedHashMap<>();
    private final Map<String, IrFunction> materializedFunctions = new LinkedHashMap<>();
    private final Map<String, FunctionRequest> functionRequests = new LinkedHashMap<>();
    private final ArrayDeque<FunctionRequest> functionQueue = new ArrayDeque<>();
    private final Map<String, ClassContext> classContexts = new LinkedHashMap<>();
    private final Map<String, ClassRequest> classRequests = new LinkedHashMap<>();
    private final ArrayDeque<ClassRequest> classQueue = new ArrayDeque<>();
    private final List<IrDispatchSlot> dispatchSlots = new ArrayList<>();
    private final Map<DispatchShape, IrDispatchSlot> specializedSlots = new LinkedHashMap<>();
    private final Set<String> reportedInvalidUses = new LinkedHashSet<>();
    private Map<Integer, IrType> activeValueTypes = Map.of();
    private int nextTypeId;

    PrimitiveGenericSpecializer(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                                List<Diagnostic> diagnostics) {
        this.types = types;
        this.hierarchy = hierarchy;
        this.diagnostics = diagnostics;
    }

    IrProgram specialize(IrProgram program) {
        program.functions().forEach(function -> originalFunctions.put(
                function.linkageName(), function));
        program.classes().forEach(type -> originalClasses.put(type.name(), type));
        dispatchSlots.addAll(program.dispatchSlots());
        nextTypeId = program.classes().stream().mapToInt(IrClass::typeId).max().orElse(-1) + 1;
        for (TypeSymbol type : types.values()) {
            type.staticInitializer().ifPresent(callable -> callables.put(
                    callable.linkageName(), callable));
            type.constructors().forEach(callable -> callables.put(callable.linkageName(), callable));
            type.destructor().ifPresent(callable -> callables.put(
                    callable.linkageName(), callable));
            type.declaredMethods().values().forEach(callable -> callables.put(
                    callable.linkageName(), callable));
        }

        List<IrStaticField> rewrittenStatics = program.staticFields().stream()
                .map(this::rewriteStaticField).toList();
        for (IrFunction function : program.functions()) {
            IrFunction rewritten = rewriteFunction(function, Map.of(), function.linkageName());
            materializedFunctions.put(rewritten.linkageName(), rewritten);
        }
        for (IrClass type : program.classes()) {
            TypeSymbol symbol = types.get(type.name());
            IrType exactType = symbol == null ? IrType.reference(type.name()) : symbol.selfType();
            classContexts.put(type.name(), new ClassContext(type, exactType, type.name(),
                    type.typeId()));
        }

        drainRequests();
        while (true) {
            int functionsBefore = functionRequests.size();
            int classesBefore = classRequests.size();
            int slotsBefore = specializedSlots.size();
            materializeClasses(true);
            drainRequests();
            if (functionsBefore == functionRequests.size()
                    && classesBefore == classRequests.size()
                    && slotsBefore == specializedSlots.size()
                    && functionQueue.isEmpty() && classQueue.isEmpty()) {
                break;
            }
        }
        List<IrClass> rewrittenClasses = materializeClasses(false);
        drainRequests();

        IrFunction entry = program.entryPoint().map(original ->
                materializedFunctions.get(original.linkageName())).orElse(null);
        return new IrProgram(program.moduleName(), rewrittenClasses, rewrittenStatics,
                program.typeInitializations(), List.of(), program.stringConstants(),
                List.copyOf(dispatchSlots), List.copyOf(materializedFunctions.values()),
                Optional.ofNullable(entry), program.allocationFailure());
    }

    private void drainRequests() {
        while (!classQueue.isEmpty() || !functionQueue.isEmpty()) {
            while (!classQueue.isEmpty()) {
                ClassRequest request = classQueue.removeFirst();
                IrClass original = originalClasses.get(request.rawName());
                if (original != null) {
                    classContexts.putIfAbsent(request.specializedName(), new ClassContext(
                            original, request.exactType(), request.specializedName(),
                            request.typeId()));
                }
            }
            while (!functionQueue.isEmpty()) {
                FunctionRequest request = functionQueue.removeFirst();
                IrFunction original = originalFunctions.get(request.baseLinkage());
                if (original == null) {
                    continue;
                }
                IrFunction rewritten = rewriteFunction(original, request.substitutions(),
                        request.specializedLinkage());
                materializedFunctions.putIfAbsent(rewritten.linkageName(), rewritten);
            }
        }
    }

    private List<IrClass> materializeClasses(boolean discoveryOnly) {
        List<IrClass> result = new ArrayList<>();
        for (ClassContext context : List.copyOf(classContexts.values())) {
            IrClass rewritten = rewriteClass(context);
            if (!discoveryOnly) {
                result.add(rewritten);
            }
        }
        return List.copyOf(result);
    }

    private IrClass rewriteClass(ClassContext context) {
        IrClass original = context.original();
        TypeSymbol symbol = types.get(original.name());
        if (symbol == null) {
            return original;
        }
        List<IrField> fields = original.fields().stream()
                .map(field -> specializeField(field, context.exactType())).toList();
        Optional<String> superclass = hierarchy.superclassType(context.exactType())
                .map(this::physicalType).filter(IrType::isNominalReference)
                .map(IrType::referenceName);
        List<String> interfaces = hierarchy.directInterfaceTypes(context.exactType()).stream()
                .map(this::physicalType).filter(IrType::isNominalReference)
                .map(IrType::referenceName).toList();
        LinkedHashSet<Integer> membership = new LinkedHashSet<>(original.typeMembership());
        hierarchy.exactSupertypes(context.exactType()).stream()
                .filter(type -> type.typeArguments().stream().anyMatch(IrType::isPrimitive))
                .forEach(type -> {
                    TypeSymbol raw = types.get(type.referenceName());
                    if (raw != null && raw.irClass() != null) {
                        membership.remove(raw.irClass().typeId());
                    }
                    IrType physical = physicalType(type);
                    membership.add(physicalTypeId(physical,
                            raw == null || raw.irClass() == null ? -1 : raw.irClass().typeId()));
                });
        membership.remove(-1);
        membership.add(context.typeId());

        List<IrDispatchEntry> entries = new ArrayList<>();
        Map<Integer, IrDispatchEntry> rawEntries = new LinkedHashMap<>();
        for (IrDispatchEntry entry : original.dispatchEntries()) {
            rawEntries.put(entry.slot().index(), entry);
            String target = specializeDispatchTarget(entry.targetLinkageName(),
                    context.exactType(), Map.of());
            entries.add(new IrDispatchEntry(entry.slot(), target));
        }
        for (Map.Entry<DispatchShape, IrDispatchSlot> specialized : specializedSlots.entrySet()) {
            DispatchShape shape = specialized.getKey();
            IrDispatchEntry raw = rawEntries.get(shape.rawSlotIndex());
            if (raw == null) {
                continue;
            }
            CallableSymbol target = callables.get(raw.targetLinkageName());
            Map<String, IrType> callableSubstitution = new LinkedHashMap<>();
            if (target != null) {
                for (int index = 0; index < Math.min(target.typeVariables().size(),
                        shape.callableKinds().size()); index++) {
                    IrType.Kind kind = shape.callableKinds().get(index);
                    if (kind != null) {
                        callableSubstitution.put(target.typeVariables().get(index).id(),
                                primitive(kind));
                    }
                }
            }
            String linkage = specializeDispatchTarget(raw.targetLinkageName(),
                    context.exactType(), callableSubstitution);
            entries.add(new IrDispatchEntry(specialized.getValue(), linkage));
        }
        Optional<String> destructor = original.destructorChain().map(linkage ->
                specializeDispatchTarget(linkage, context.exactType(), Map.of()));
        Optional<String> rollback = original.constructorRollback().map(linkage ->
                requestFunction(linkage, primitiveOwnerSubstitution(
                        context.exactType(), original.name())));
        return new IrClass(context.outputName(), original.kind(), superclass, interfaces,
                fields, context.typeId(), List.copyOf(membership), entries, destructor, rollback,
                original.toStringReturnsOwnedFresh(), original.localizedMessageReturnsOwnedFresh(),
                original.sourceSpan());
    }

    private String specializeDispatchTarget(String baseLinkage, IrType exactReceiver,
                                            Map<String, IrType> callableSubstitution) {
        CallableSymbol target = callables.get(baseLinkage);
        if (target == null) {
            return baseLinkage;
        }
        Map<String, IrType> substitutions = new LinkedHashMap<>(
                primitiveOwnerSubstitution(exactReceiver, target.ownerType()));
        substitutions.putAll(callableSubstitution);
        return requestFunction(baseLinkage, substitutions);
    }

    private IrFunction rewriteFunction(IrFunction function, Map<String, IrType> substitutions,
                                       String linkageName) {
        Map<Integer, IrType> previousValueTypes = activeValueTypes;
        activeValueTypes = equalityBridgeTypes(function, substitutions);
        try {
            List<IrParameter> parameters = function.parameters().stream()
                    .map(parameter -> new IrParameter(parameter.name(),
                            value(parameter.value(), substitutions), parameter.sourceSpan())).toList();
            List<IrBasicBlock> blocks = function.blocks().stream()
                    .map(block -> new IrBasicBlock(block.label(), block.instructions().stream()
                            .map(instruction -> instruction(instruction, substitutions, function))
                            .toList(), terminator(block.terminator(), substitutions, function),
                            block.sourceSpan())).toList();
            return new IrFunction(function.ownerClass(), function.sourceName(), linkageName,
                    physicalType(function.returnType().substitute(substitutions)), parameters,
                    blocks, function.sourceSpan(), function.sourceFileName(), function.kind());
        } finally {
            activeValueTypes = previousValueTypes;
        }
    }

    private Map<Integer, IrType> equalityBridgeTypes(IrFunction function,
                                                     Map<String, IrType> substitutions) {
        Map<Integer, IrReferenceConversionInstruction> conversions = new LinkedHashMap<>();
        function.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(IrReferenceConversionInstruction.class::isInstance)
                .map(IrReferenceConversionInstruction.class::cast)
                .forEach(conversion -> conversions.put(conversion.result().id(), conversion));
        Map<Integer, IrType> result = new LinkedHashMap<>();
        function.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(IrBinaryInstruction.class::isInstance)
                .map(IrBinaryInstruction.class::cast)
                .filter(binary -> binary.operator() == IrBinaryOperator.EQUAL
                        || binary.operator() == IrBinaryOperator.NOT_EQUAL)
                .forEach(binary -> {
                    IrReferenceConversionInstruction left = conversionFor(
                            binary.left(), conversions);
                    IrReferenceConversionInstruction right = conversionFor(
                            binary.right(), conversions);
                    if (left == null || right == null) {
                        return;
                    }
                    IrType leftType = physicalType(left.value().type().substitute(substitutions));
                    IrType rightType = physicalType(right.value().type().substitute(substitutions));
                    if (leftType.isPrimitive() && leftType.equals(rightType)) {
                        result.put(left.result().id(), leftType);
                        result.put(right.result().id(), rightType);
                    }
                });
        return Map.copyOf(result);
    }

    private static IrReferenceConversionInstruction conversionFor(
            IrOperand operand, Map<Integer, IrReferenceConversionInstruction> conversions) {
        return operand instanceof IrValueReference value ? conversions.get(value.id()) : null;
    }

    private IrInstruction instruction(IrInstruction instruction,
                                      Map<String, IrType> substitutions,
                                      IrFunction function) {
        if (instruction instanceof IrAllocateInstruction value) {
            IrValueReference result = value(value.result(), substitutions);
            return new IrAllocateInstruction(result, result.type().referenceName(), value.sourceSpan());
        }
        if (instruction instanceof IrAllocationCountInstruction value) {
            return new IrAllocationCountInstruction(value(value.result(), substitutions),
                    value.sourceSpan());
        }
        if (instruction instanceof IrLiveAllocationCountInstruction value) {
            return new IrLiveAllocationCountInstruction(value(value.result(), substitutions),
                    value.sourceSpan());
        }
        if (instruction instanceof IrArrayAllocateInstruction value) {
            return new IrArrayAllocateInstruction(value(value.result(), substitutions),
                    physicalType(value.elementType().substitute(substitutions)),
                    operand(value.length(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrArrayBoundsCheckInstruction value) {
            return new IrArrayBoundsCheckInstruction(value(value.result(), substitutions),
                    operand(value.array(), substitutions, function),
                    operand(value.index(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrArrayLengthCheckInstruction value) {
            return new IrArrayLengthCheckInstruction(value(value.result(), substitutions),
                    operand(value.length(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrArrayLengthInstruction value) {
            return new IrArrayLengthInstruction(value(value.result(), substitutions),
                    operand(value.array(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrArrayLoadInstruction value) {
            return new IrArrayLoadInstruction(value(value.result(), substitutions),
                    operand(value.array(), substitutions, function),
                    operand(value.index(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrArrayStoreInstruction value) {
            return new IrArrayStoreInstruction(operand(value.array(), substitutions, function),
                    operand(value.index(), substitutions, function),
                    operand(value.value(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrArrayTypeTestInstruction value) {
            return new IrArrayTypeTestInstruction(value(value.result(), substitutions),
                    operand(value.value(), substitutions, function),
                    physicalType(value.targetType().substitute(substitutions)), value.sourceSpan());
        }
        if (instruction instanceof IrBinaryInstruction value) {
            return new IrBinaryInstruction(value(value.result(), substitutions), value.operator(),
                    operand(value.left(), substitutions, function),
                    operand(value.right(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrCallInstruction value) {
            return directCall(value, substitutions, function);
        }
        if (instruction instanceof IrEnsureTypeInitializedInstruction) {
            return instruction;
        }
        if (instruction instanceof IrExceptionCaughtInstruction value) {
            return new IrExceptionCaughtInstruction(
                    operand(value.exception(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrExceptionLandingPadInstruction value) {
            return new IrExceptionLandingPadInstruction(value(value.exceptionHandle(), substitutions),
                    value(value.exceptionObject(), substitutions), value.sourceSpan());
        }
        if (instruction instanceof IrFieldLoadInstruction value) {
            IrType sourceReceiver = value.receiver().type().substitute(substitutions);
            IrField field = specializeField(value.field(), sourceReceiver);
            return new IrFieldLoadInstruction(value(value.result(), substitutions),
                    operand(value.receiver(), substitutions, function), field, value.sourceSpan());
        }
        if (instruction instanceof IrFieldStoreInstruction value) {
            IrType sourceReceiver = value.receiver().type().substitute(substitutions);
            IrField field = specializeField(value.field(), sourceReceiver);
            return new IrFieldStoreInstruction(operand(value.receiver(), substitutions, function),
                    field, operand(value.value(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStreamInstruction value) {
            return new IrStreamInstruction(value(value.result(), substitutions), value.operation(),
                    value.arguments().stream().map(item -> operand(item, substitutions, function)).toList(),
                    value.sourceSpan());
        }
        if (instruction instanceof IrFileInstruction value) {
            return new IrFileInstruction(value(value.result(), substitutions), value.operation(),
                    value.path().map(path -> operand(path, substitutions, function)),
                    value.value().map(item -> operand(item, substitutions, function)),
                    value.sourceSpan());
        }
        if (instruction instanceof IrFloatingParseInstruction value) {
            return new IrFloatingParseInstruction(value(value.result(), substitutions),
                    operand(value.text(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrDestroyArrayElementsInstruction value) {
            return new IrDestroyArrayElementsInstruction(
                    operand(value.array(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrFreeInstruction value) {
            IrOperand allocation = operand(value.allocation(), substitutions, function);
            if (allocation.type().isPrimitive()) {
                invalid(function, value.sourceSpan(), "primitive specialization cannot free a value");
                return instruction;
            }
            return new IrFreeInstruction(allocation, value.sourceSpan());
        }
        if (instruction instanceof IrIdentityHashCodeInstruction value) {
            IrOperand object = operand(value.object(), substitutions, function);
            if (object.type().isPrimitive()) {
                invalid(function, value.sourceSpan(), "primitive specialization cannot use a value as Object");
                return instruction;
            }
            return new IrIdentityHashCodeInstruction(value(value.result(), substitutions), object,
                    value.sourceSpan());
        }
        if (instruction instanceof IrInstanceOfInstruction value) {
            IrOperand object = operand(value.value(), substitutions, function);
            if (object.type().isPrimitive()) {
                invalid(function, value.sourceSpan(), "primitive specialization cannot apply instanceof to its value");
                return instruction;
            }
            String targetName = value.targetTypeName();
            int targetTypeId = value.targetTypeId();
            Optional<IrType> exactTarget = value.exactTargetType().map(type ->
                    type.substitute(substitutions));
            if (exactTarget.isPresent()) {
                IrType physicalTarget = physicalType(exactTarget.orElseThrow());
                if (physicalTarget.isNominalReference()) {
                    targetName = physicalTarget.referenceName();
                    targetTypeId = physicalTypeId(physicalTarget, targetTypeId);
                }
            }
            return new IrInstanceOfInstruction(value(value.result(), substitutions), object,
                    targetName, targetTypeId, exactTarget, value.sourceSpan());
        }
        if (instruction instanceof IrInterfaceCallInstruction value) {
            return interfaceCall(value, substitutions, function);
        }
        if (instruction instanceof IrNullCheckInstruction value) {
            IrOperand receiver = operand(value.receiver(), substitutions, function);
            if (receiver.type().isPrimitive()) {
                invalid(function, value.sourceSpan(), "primitive specialization cannot perform a null check");
                return instruction;
            }
            return new IrNullCheckInstruction(value(value.result(), substitutions),
                    receiver, value.sourceSpan());
        }
        if (instruction instanceof IrNumericConversionInstruction value) {
            return new IrNumericConversionInstruction(value(value.result(), substitutions),
                    operand(value.value(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrObjectHashCodeInstruction value) {
            return referenceIntrinsic(value.object(), value.sourceSpan(), function, substitutions,
                    object -> new IrObjectHashCodeInstruction(value(value.result(), substitutions),
                            object, value.sourceSpan()));
        }
        if (instruction instanceof IrThrowableTraceInstruction trace) {
            return new IrThrowableTraceInstruction(trace.result().map(result -> value(result, substitutions)),
                    trace.operation(), trace.arguments().stream().map(argument -> operand(argument, substitutions, function)).toList(),
                    trace.sourceSpan());
        }
        if (instruction instanceof IrThrowableDescriptionInstruction value) {
            return new IrThrowableDescriptionInstruction(value(value.result(), substitutions),
                    operand(value.throwable(), substitutions, function),
                    operand(value.message(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrReleaseOwnedThrowableMessageInstruction value) {
            return new IrReleaseOwnedThrowableMessageInstruction(
                    operand(value.throwable(), substitutions, function),
                    operand(value.message(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrObjectToStringInstruction value) {
            return referenceIntrinsic(value.object(), value.sourceSpan(), function, substitutions,
                    object -> new IrObjectToStringInstruction(value(value.result(), substitutions),
                            object, value.sourceSpan()));
        }
        if (instruction instanceof IrPhiInstruction value) {
            return new IrPhiInstruction(value(value.result(), substitutions), value.incoming().stream()
                    .map(incoming -> new IrPhiIncoming(incoming.predecessor(),
                            operand(incoming.value(), substitutions, function))).toList(),
                    value.sourceSpan());
        }
        if (instruction instanceof IrPrintStreamPrintlnInstruction value) {
            return new IrPrintStreamPrintlnInstruction(
                    operand(value.value(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrPrintStreamWriteInstruction value) {
            return new IrPrintStreamWriteInstruction(
                    operand(value.stream(), substitutions, function),
                    value.value().map(part -> new IrStringConcatPart(part.kind(),
                            operand(part.value(), substitutions, function))),
                    value.newline(), value.sourceSpan());
        }
        if (instruction instanceof IrPrintStreamFlushInstruction value) {
            return new IrPrintStreamFlushInstruction(
                    operand(value.stream(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrPrintStreamCheckErrorInstruction value) {
            return new IrPrintStreamCheckErrorInstruction(
                    value(value.result(), substitutions),
                    operand(value.stream(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrReferenceConversionInstruction value) {
            IrValueReference result = value(value.result(), substitutions);
            IrOperand input = operand(value.value(), substitutions, function);
            if (activeValueTypes.containsKey(value.result().id())) {
                return new IrBinaryInstruction(result,
                        result.type().equals(IrType.I1)
                                ? IrBinaryOperator.BITWISE_OR : IrBinaryOperator.ADD,
                        input, new IrConstant(result.type(), 0, value.sourceSpan()),
                        value.sourceSpan());
            }
            if (result.type().isPrimitive() || input.type().isPrimitive()) {
                invalid(function, value.sourceSpan(),
                        "primitive specialization cannot perform a reference conversion");
                return instruction;
            }
            return new IrReferenceConversionInstruction(result, input, value.sourceSpan());
        }
        if (instruction instanceof IrRawDeallocateInstruction value) {
            return new IrRawDeallocateInstruction(
                    operand(value.allocation(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrReleaseOwnedToStringResultInstruction value) {
            return new IrReleaseOwnedToStringResultInstruction(
                    operand(value.object(), substitutions, function),
                    operand(value.result(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrRollbackInstruction value) {
            return new IrRollbackInstruction(
                    operand(value.allocation(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrSecondaryExceptionAtInstruction value) {
            return new IrSecondaryExceptionAtInstruction(value(value.result(), substitutions),
                    operand(value.primary(), substitutions, function),
                    operand(value.index(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrSecondaryExceptionCountInstruction value) {
            return new IrSecondaryExceptionCountInstruction(value(value.result(), substitutions),
                    operand(value.primary(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrAddSecondaryExceptionInstruction value) {
            return new IrAddSecondaryExceptionInstruction(
                    operand(value.primary(), substitutions, function),
                    operand(value.secondary(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStaticFieldLoadInstruction value) {
            IrStaticField field = staticFields.getOrDefault(value.field(), value.field());
            return new IrStaticFieldLoadInstruction(value(value.result(), substitutions), field,
                    value.sourceSpan());
        }
        if (instruction instanceof IrStaticFieldStoreInstruction value) {
            IrStaticField field = staticFields.getOrDefault(value.field(), value.field());
            return new IrStaticFieldStoreInstruction(field,
                    operand(value.value(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStringCharAtInstruction value) {
            return new IrStringCharAtInstruction(value(value.result(), substitutions),
                    operand(value.string(), substitutions, function),
                    operand(value.index(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStringConcatInstruction value) {
            return new IrStringConcatInstruction(value(value.result(), substitutions),
                    value.parts().stream().map(part -> new IrStringConcatPart(part.kind(),
                            operand(part.value(), substitutions, function))).toList(),
                    value.sourceSpan());
        }
        if (instruction instanceof IrStringEqualsInstruction value) {
            return new IrStringEqualsInstruction(value(value.result(), substitutions),
                    operand(value.string(), substitutions, function),
                    operand(value.other(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStringCopyInstruction value) {
            return new IrStringCopyInstruction(value(value.result(), substitutions),
                    operand(value.source(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStringFromCharsInstruction value) {
            return new IrStringFromCharsInstruction(value(value.result(), substitutions),
                    operand(value.characters(), substitutions, function),
                    operand(value.length(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStringCaseInstruction value) {
            return new IrStringCaseInstruction(value(value.result(), substitutions),
                    operand(value.source(), substitutions, function),
                    operand(value.upper(), substitutions, function),
                    value.sourceSpan());
        }
        if (instruction instanceof IrStringRepeatInstruction value) {
            return new IrStringRepeatInstruction(value(value.result(), substitutions),
                    operand(value.source(), substitutions, function),
                    operand(value.count(), substitutions, function),
                    value.sourceSpan());
        }
        if (instruction instanceof IrStringReplaceCharInstruction value) {
            return new IrStringReplaceCharInstruction(value(value.result(), substitutions),
                    operand(value.source(), substitutions, function),
                    operand(value.oldChar(), substitutions, function),
                    operand(value.newChar(), substitutions, function),
                    value.sourceSpan());
        }
        if (instruction instanceof IrStringReplaceTextInstruction value) {
            return new IrStringReplaceTextInstruction(value(value.result(), substitutions),
                    operand(value.source(), substitutions, function),
                    operand(value.target(), substitutions, function),
                    operand(value.replacement(), substitutions, function),
                    value.sourceSpan());
        }
        if (instruction instanceof IrStringEqualsIgnoreCaseInstruction value) {
            return new IrStringEqualsIgnoreCaseInstruction(value(value.result(), substitutions),
                    operand(value.source(), substitutions, function),
                    operand(value.other(), substitutions, function),
                    value.sourceSpan());
        }
        if (instruction instanceof IrStringJoinInstruction value) {
            return new IrStringJoinInstruction(value(value.result(), substitutions),
                    operand(value.delimiter(), substitutions, function),
                    operand(value.elements(), substitutions, function),
                    value.sourceSpan());
        }
        if (instruction instanceof IrStringFromUtf8Instruction value) {
            return new IrStringFromUtf8Instruction(value(value.result(), substitutions),
                    operand(value.bytes(), substitutions, function),
                    operand(value.length(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStringFromCharRangeInstruction value) {
            return new IrStringFromCharRangeInstruction(value(value.result(), substitutions),
                    operand(value.characters(), substitutions, function),
                    operand(value.offset(), substitutions, function),
                    operand(value.length(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStringFromRangeInstruction value) {
            return new IrStringFromRangeInstruction(value(value.result(), substitutions),
                    operand(value.source(), substitutions, function),
                    operand(value.beginIndex(), substitutions, function),
                    operand(value.length(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStringFromIntegerInstruction value) {
            return new IrStringFromIntegerInstruction(value(value.result(), substitutions),
                    operand(value.value(), substitutions, function),
                    operand(value.radix(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStringFromCharacterInstruction value) {
            return new IrStringFromCharacterInstruction(value(value.result(), substitutions),
                    operand(value.value(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrStringHashCodeInstruction value) {
            return new IrStringHashCodeInstruction(value(value.result(), substitutions),
                    operand(value.string(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrSystemArrayCopyInstruction value) {
            return new IrSystemArrayCopyInstruction(operand(value.source(), substitutions, function),
                    operand(value.sourcePosition(), substitutions, function),
                    operand(value.destination(), substitutions, function),
                    operand(value.destinationPosition(), substitutions, function),
                    operand(value.length(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrSystemGetenvInstruction value) {
            return new IrSystemGetenvInstruction(value(value.result(), substitutions),
                    operand(value.name(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof ironwood.compiler.ir.IrSystemPropertyInstruction value) {
            return new ironwood.compiler.ir.IrSystemPropertyInstruction(
                    value(value.result(), substitutions),
                    operand(value.name(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof ironwood.compiler.ir.IrSystemExitInstruction value) {
            return new ironwood.compiler.ir.IrSystemExitInstruction(
                    operand(value.status(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrSystemClockInstruction value) {
            return new IrSystemClockInstruction(value(value.result(), substitutions),
                    value.clock(), value.sourceSpan());
        }
        if (instruction instanceof ironwood.compiler.ir.IrCharacterInstruction value) {
            return new ironwood.compiler.ir.IrCharacterInstruction(
                    value(value.result(), substitutions), value.operation(),
                    operand(value.codePoint(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof ironwood.compiler.ir.IrFloatingBitsInstruction value) {
            return new ironwood.compiler.ir.IrFloatingBitsInstruction(
                    value(value.result(), substitutions), value.operation(),
                    operand(value.value(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrMathUnaryInstruction value) {
            return new IrMathUnaryInstruction(value(value.result(), substitutions),
                    value.operation(), operand(value.value(), substitutions, function),
                    value.sourceSpan());
        }
        if (instruction instanceof IrMathBinaryInstruction value) {
            return new IrMathBinaryInstruction(value(value.result(), substitutions),
                    value.operation(), operand(value.left(), substitutions, function),
                    operand(value.right(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrUnaryInstruction value) {
            return new IrUnaryInstruction(value(value.result(), substitutions), value.operator(),
                    operand(value.operand(), substitutions, function), value.sourceSpan());
        }
        if (instruction instanceof IrVirtualCallInstruction value) {
            return virtualCall(value, substitutions, function);
        }
        throw new IllegalStateException("unsupported specialization instruction "
                + instruction.getClass().getSimpleName());
    }

    private IrInstruction referenceIntrinsic(IrOperand original, SourceSpan span,
                                             IrFunction function,
                                             Map<String, IrType> substitutions,
                                             java.util.function.Function<IrOperand, IrInstruction> factory) {
        IrOperand object = operand(original, substitutions, function);
        if (object.type().isPrimitive()) {
            invalid(function, span, "primitive specialization cannot use Object members");
            return factory.apply(original);
        }
        return factory.apply(object);
    }

    private IrInstruction directCall(IrCallInstruction call,
                                     Map<String, IrType> substitutions,
                                     IrFunction function) {
        List<IrType> sourceArguments = call.arguments().stream()
                .map(argument -> argument.type().substitute(substitutions)).toList();
        IrType sourceReturn = call.returnType().substitute(substitutions);
        Map<String, IrType> requested = substitutedPrimitiveArguments(
                call.specializationArguments(), substitutions);
        IrFunction target = originalFunctions.get(call.targetLinkageName());
        if (target != null) {
            for (int index = 0; index < Math.min(target.parameters().size(),
                    sourceArguments.size()); index++) {
                inferPrimitiveSubstitutions(target.parameters().get(index).value().type(),
                        sourceArguments.get(index), requested);
            }
            inferPrimitiveSubstitutions(target.returnType(), sourceReturn, requested);
        }
        List<IrOperand> arguments = call.arguments().stream()
                .map(argument -> operand(argument, substitutions, function)).toList();
        if (!arguments.isEmpty() && arguments.getFirst().type().isPrimitive()
                && call.callKind() != IrCallKind.DIRECT) {
            invalid(function, call.sourceSpan(),
                    "primitive specialization cannot dispatch through an Object receiver");
            return call;
        }
        return new IrCallInstruction(call.result().map(result -> value(result, substitutions)),
                requestFunction(call.targetLinkageName(), requested),
                physicalType(sourceReturn), arguments, call.callKind(), call.devirtualizedFrom(),
                requested, call.sourceSpan());
    }

    private IrInstruction virtualCall(IrVirtualCallInstruction call,
                                      Map<String, IrType> substitutions,
                                      IrFunction function) {
        List<IrOperand> arguments = call.arguments().stream()
                .map(argument -> operand(argument, substitutions, function)).toList();
        if (!arguments.isEmpty() && arguments.getFirst().type().isPrimitive()) {
            invalid(function, call.sourceSpan(),
                    "primitive specialization cannot use a value as a virtual receiver");
            return call;
        }
        Map<String, IrType> requested = substitutedPrimitiveArguments(
                call.specializationArguments(), substitutions);
        IrDispatchSlot slot = specializedDispatchSlot(call.slot(), requested,
                physicalType(call.returnType().substitute(substitutions)), arguments);
        return new IrVirtualCallInstruction(
                call.result().map(result -> value(result, substitutions)), slot,
                physicalType(call.returnType().substitute(substitutions)), arguments,
                requested, call.sourceSpan());
    }

    private IrInstruction interfaceCall(IrInterfaceCallInstruction call,
                                        Map<String, IrType> substitutions,
                                        IrFunction function) {
        List<IrOperand> arguments = call.arguments().stream()
                .map(argument -> operand(argument, substitutions, function)).toList();
        if (!arguments.isEmpty() && arguments.getFirst().type().isPrimitive()) {
            invalid(function, call.sourceSpan(),
                    "primitive specialization cannot use a value as an interface receiver");
            return call;
        }
        Map<String, IrType> requested = substitutedPrimitiveArguments(
                call.specializationArguments(), substitutions);
        IrDispatchSlot slot = specializedDispatchSlot(call.slot(), requested,
                physicalType(call.returnType().substitute(substitutions)), arguments);
        return new IrInterfaceCallInstruction(
                call.result().map(result -> value(result, substitutions)), call.interfaceName(),
                slot, physicalType(call.returnType().substitute(substitutions)), arguments,
                requested, call.sourceSpan());
    }

    private IrDispatchSlot specializedDispatchSlot(IrDispatchSlot raw,
                                                   Map<String, IrType> substitutions,
                                                   IrType returnType,
                                                   List<IrOperand> arguments) {
        List<IrType.Kind> kinds = callablePrimitiveKinds(raw, substitutions);
        if (kinds.stream().allMatch(java.util.Objects::isNull)) {
            return raw;
        }
        DispatchShape shape = new DispatchShape(raw.index(), kinds);
        return specializedSlots.computeIfAbsent(shape, ignored -> {
            IrDispatchSlot slot = new IrDispatchSlot(dispatchSlots.size(),
                    raw.key() + "$specialization$" + encodeKinds(kinds), raw.methodName(),
                    returnType, arguments.size() <= 1 ? List.of()
                    : arguments.subList(1, arguments.size()).stream()
                            .map(IrOperand::type).toList(), raw.sourceSpan());
            dispatchSlots.add(slot);
            return slot;
        });
    }

    private List<IrType.Kind> callablePrimitiveKinds(IrDispatchSlot slot,
                                                     Map<String, IrType> substitutions) {
        CallableSymbol selected = callables.values().stream()
                .filter(callable -> callable.dispatchKey().equals(slot.key()))
                .filter(callable -> callable.typeVariables().stream()
                        .anyMatch(variable -> substitutions.containsKey(variable.id())))
                .findFirst().orElse(null);
        if (selected == null || selected.typeVariables().isEmpty()) {
            return List.of();
        }
        return selected.typeVariables().stream().map(variable -> {
            IrType value = substitutions.get(variable.id());
            return value != null && value.isPrimitive() ? value.kind() : null;
        }).toList();
    }

    private IrTerminator terminator(IrTerminator terminator,
                                    Map<String, IrType> substitutions,
                                    IrFunction function) {
        if (terminator instanceof IrBranch value) {
            return new IrBranch(operand(value.condition(), substitutions, function),
                    value.trueTarget(), value.falseTarget(), value.sourceSpan());
        }
        if (terminator instanceof IrInvokeTerminator value) {
            return new IrInvokeTerminator(instruction(value.call(), substitutions, function),
                    value.normalTarget(), value.unwindTarget(), value.sourceSpan());
        }
        if (terminator instanceof IrJump || terminator instanceof IrUnreachable) {
            return terminator;
        }
        if (terminator instanceof IrReturnTerminator value) {
            return new IrReturnTerminator(value.value().map(result ->
                    operand(result, substitutions, function)), value.sourceSpan());
        }
        if (terminator instanceof IrSwitchTerminator value) {
            return new IrSwitchTerminator(operand(value.selector(), substitutions, function),
                    value.cases().stream().map(branch -> new IrSwitchCase(
                            (IrConstant) operand(branch.value(), substitutions, function),
                            branch.target(), branch.sourceSpan())).toList(),
                    value.defaultTarget(), value.sourceSpan());
        }
        if (terminator instanceof IrThrowTerminator value) {
            IrOperand exception = operand(value.exception(), substitutions, function);
            if (exception.type().isPrimitive()) {
                invalid(function, value.sourceSpan(),
                        "primitive specialization cannot throw its value");
                return terminator;
            }
            return new IrThrowTerminator(exception, value.normalTarget(), value.unwindTarget(),
                    value.sourceSpan());
        }
        throw new IllegalStateException("unsupported specialization terminator "
                + terminator.getClass().getSimpleName());
    }

    private IrOperand operand(IrOperand operand, Map<String, IrType> substitutions,
                              IrFunction function) {
        if (operand instanceof IrValueReference value) {
            return value(value, substitutions);
        }
        if (operand instanceof IrConstant value) {
            return new IrConstant(physicalType(value.type().substitute(substitutions)),
                    value.value(), value.sourceSpan());
        }
        if (operand instanceof IrNull value) {
            IrType type = physicalType(value.type().substitute(substitutions));
            if (type.isPrimitive()) {
                invalid(function, value.sourceSpan(),
                        "primitive specialization cannot use null for its value");
                return new IrNull(IrType.reference("ironwood.lang.Object"), value.sourceSpan());
            }
            return new IrNull(type, value.sourceSpan());
        }
        if (operand instanceof IrImmortalObject value) {
            return new IrImmortalObject(value.symbol(),
                    physicalType(value.type().substitute(substitutions)),
                    physicalType(value.storageType().substitute(substitutions)), value.fieldValues(),
                    value.sourceSpan());
        }
        if (operand instanceof IrEnumConstant value) {
            return new IrEnumConstant(value.symbol(),
                    physicalType(value.type().substitute(substitutions)),
                    physicalType(value.storageType().substitute(substitutions)),
                    value.constantName(), value.ordinal(), value.nameValue(), value.sourceSpan());
        }
        return operand;
    }

    private IrValueReference value(IrValueReference value,
                                   Map<String, IrType> substitutions) {
        IrType override = activeValueTypes.get(value.id());
        if (override != null) {
            return new IrValueReference(value.id(), override, value.sourceSpan());
        }
        return new IrValueReference(value.id(),
                physicalType(value.type().substitute(substitutions)), value.sourceSpan());
    }

    private IrStaticField rewriteStaticField(IrStaticField field) {
        IrType type = physicalType(field.type());
        IrOperand initial = operand(field.initialValue(), Map.of(), nullFunction(field));
        if (initial instanceof IrNull && !initial.type().equals(type)) {
            initial = new IrNull(type, initial.sourceSpan());
        }
        IrStaticField rewritten = new IrStaticField(field.ownerClass(), field.name(), type,
                field.isFinal(), field.compileTimeConstant(), field.preinitializedIntrinsic(),
                initial, field.sourceSpan());
        staticFields.put(field, rewritten);
        return rewritten;
    }

    private IrFunction nullFunction(IrStaticField field) {
        return new IrFunction(field.ownerClass(), "<static>", "<static>", IrType.VOID,
                List.of(), List.of(), field.sourceSpan());
    }

    private IrField specializeField(IrField field, IrType exactReceiver) {
        TypeSymbol owner = types.get(field.ownerClass());
        IrType ownerView = owner == null ? null : exactOwnerView(exactReceiver, owner);
        if (owner == null || ownerView == null) {
            return new IrField(field.ownerClass(), field.name(), physicalType(field.type()),
                    field.layoutIndex(), field.sourceSpan());
        }
        Map<String, IrType> substitutions = owner.substitutionFor(ownerView);
        IrType physicalOwner = physicalType(ownerView);
        return new IrField(physicalOwner.referenceName(), field.name(),
                physicalType(field.type().substitute(substitutions)), field.layoutIndex(),
                field.sourceSpan());
    }

    private IrType exactOwnerView(IrType receiver, TypeSymbol owner) {
        if (!receiver.isNominalReference()) {
            return null;
        }
        if (receiver.referenceName().equals(owner.name())) {
            return receiver;
        }
        return hierarchy.exactSupertypes(receiver).stream()
                .filter(type -> type.isNominalReference()
                        && type.referenceName().equals(owner.name()))
                .findFirst().orElse(null);
    }

    private Map<String, IrType> primitiveOwnerSubstitution(IrType exactReceiver,
                                                          String ownerName) {
        TypeSymbol owner = types.get(ownerName);
        IrType view = owner == null ? null : exactOwnerView(exactReceiver, owner);
        if (view == null) {
            return Map.of();
        }
        return primitiveOnly(owner.substitutionFor(view));
    }

    private IrType physicalType(IrType type) {
        if (type.isArray()) {
            return IrType.array(physicalType(type.elementType()));
        }
        if (type.isWildcard()) {
            return type.wildcardBound() == null ? type
                    : IrType.wildcard(type.wildcardKind(), physicalType(type.wildcardBound()));
        }
        if (!type.isNominalReference()) {
            return type;
        }
        List<IrType> arguments = type.typeArguments();
        TypeSymbol symbol = types.get(type.referenceName());
        if (symbol != null && arguments.size() == symbol.typeParameters().size()
                && arguments.stream().anyMatch(IrType::isPrimitive)) {
            return IrType.reference(requestClass(symbol, arguments));
        }
        return IrType.reference(type.referenceName(),
                arguments.stream().map(this::physicalType).toList());
    }

    private String requestClass(TypeSymbol symbol, List<IrType> arguments) {
        List<IrType.Kind> shape = arguments.stream()
                .map(argument -> argument.isPrimitive() ? argument.kind() : null).toList();
        String specializedName = symbol.name() + "<specialization:" + encodeKinds(shape) + ">";
        classRequests.computeIfAbsent(specializedName, ignored -> {
            Map<String, IrType> substitution = new LinkedHashMap<>();
            List<TypeVariableSymbol> variables = symbol.typeParameters();
            for (int index = 0; index < variables.size(); index++) {
                if (arguments.get(index).isPrimitive()) {
                    substitution.put(variables.get(index).id(), arguments.get(index));
                }
            }
            ClassRequest request = new ClassRequest(symbol.name(), specializedName,
                    symbol.selfType().substitute(substitution), nextTypeId++);
            classQueue.addLast(request);
            return request;
        });
        return specializedName;
    }

    private int physicalTypeId(IrType type, int fallback) {
        if (!type.isNominalReference()) {
            return fallback;
        }
        ClassRequest request = classRequests.get(type.referenceName());
        if (request != null) {
            return request.typeId();
        }
        ClassContext context = classContexts.get(type.referenceName());
        if (context != null) {
            return context.typeId();
        }
        IrClass original = originalClasses.get(type.referenceName());
        return original == null ? fallback : original.typeId();
    }

    private String requestFunction(String baseLinkage, Map<String, IrType> proposed) {
        IrFunction original = originalFunctions.get(baseLinkage);
        CallableSymbol callable = callables.get(baseLinkage);
        if (original == null || callable == null) {
            return baseLinkage;
        }
        Map<String, IrType> relevant = new LinkedHashMap<>();
        TypeSymbol owner = types.get(callable.ownerType());
        if (owner != null) {
            owner.typeParameters().forEach(variable -> addPrimitive(proposed, relevant, variable.id()));
        }
        callable.typeVariables().forEach(variable -> addPrimitive(proposed, relevant, variable.id()));
        if (relevant.isEmpty()) {
            return baseLinkage;
        }
        String specialized = specializedFunctionName(baseLinkage, callable, relevant);
        functionRequests.computeIfAbsent(specialized, ignored -> {
            FunctionRequest request = new FunctionRequest(baseLinkage, specialized,
                    Map.copyOf(relevant));
            functionQueue.addLast(request);
            return request;
        });
        return specialized;
    }

    private static void addPrimitive(Map<String, IrType> source, Map<String, IrType> target,
                                     String id) {
        IrType value = source.get(id);
        if (value != null && value.isPrimitive()) {
            target.put(id, value);
        }
    }

    private String specializedFunctionName(String base, CallableSymbol callable,
                                           Map<String, IrType> substitutions) {
        List<String> parts = new ArrayList<>();
        TypeSymbol owner = types.get(callable.ownerType());
        if (owner != null) {
            for (int index = 0; index < owner.typeParameters().size(); index++) {
                IrType value = substitutions.get(owner.typeParameters().get(index).id());
                if (value != null) {
                    parts.add("C" + index + "_" + primitiveName(value.kind()));
                }
            }
        }
        for (int index = 0; index < callable.typeVariables().size(); index++) {
            IrType value = substitutions.get(callable.typeVariables().get(index).id());
            if (value != null) {
                parts.add("M" + index + "_" + primitiveName(value.kind()));
            }
        }
        return base + "$specialization$" + String.join("$", parts);
    }

    private Map<String, IrType> substitutedPrimitiveArguments(Map<String, IrType> arguments,
                                                              Map<String, IrType> substitutions) {
        Map<String, IrType> result = new LinkedHashMap<>();
        arguments.forEach((id, value) -> {
            IrType substituted = value.substitute(substitutions);
            if (substituted.isPrimitive()) {
                result.put(id, substituted);
            }
        });
        return result;
    }

    private void inferPrimitiveSubstitutions(IrType template, IrType actual,
                                             Map<String, IrType> result) {
        if (template.isTypeParameter()) {
            if (actual.isPrimitive()) {
                result.putIfAbsent(template.referenceName(), actual);
            }
            return;
        }
        if (template.isArray() && actual.isArray()) {
            inferPrimitiveSubstitutions(template.elementType(), actual.elementType(), result);
            return;
        }
        if (!template.isNominalReference() || !actual.isNominalReference()) {
            return;
        }
        IrType view = actual;
        if (!template.referenceName().equals(actual.referenceName())) {
            TypeSymbol expected = types.get(template.referenceName());
            view = expected == null ? null : exactOwnerView(actual, expected);
        }
        if (view == null || template.typeArguments().size() != view.typeArguments().size()) {
            return;
        }
        for (int index = 0; index < template.typeArguments().size(); index++) {
            inferPrimitiveSubstitutions(template.typeArguments().get(index),
                    view.typeArguments().get(index), result);
        }
    }

    private static Map<String, IrType> primitiveOnly(Map<String, IrType> source) {
        Map<String, IrType> result = new LinkedHashMap<>();
        source.forEach((id, value) -> {
            if (value.isPrimitive()) {
                result.put(id, value);
            }
        });
        return Map.copyOf(result);
    }

    private void invalid(IrFunction function, SourceSpan span, String reason) {
        String key = function.linkageName() + "@" + span.start().offset() + ":" + reason;
        if (!reportedInvalidUses.add(key)) {
            return;
        }
        TypeSymbol owner = types.get(function.ownerClass());
        SourceFile source = owner == null ? types.values().iterator().next().source() : owner.source();
        diagnostics.add(Diagnostic.error(source, span, reason + " in generic callable '"
                + function.ownerClass() + "." + function.sourceName() + "'"));
    }

    private static String encodeKinds(List<IrType.Kind> kinds) {
        List<String> names = new ArrayList<>();
        for (IrType.Kind kind : kinds) {
            names.add(kind == null ? "ref" : primitiveName(kind));
        }
        return String.join("$", names);
    }

    private static String primitiveName(IrType.Kind kind) {
        return switch (kind) {
            case I1 -> "boolean";
            case I8 -> "byte";
            case I16 -> "short";
            case U16 -> "char";
            case I32 -> "int";
            case I64 -> "long";
            case F32 -> "float";
            case F64 -> "double";
            default -> throw new IllegalArgumentException("not a primitive kind: " + kind);
        };
    }

    private static IrType primitive(IrType.Kind kind) {
        return switch (kind) {
            case I1 -> IrType.I1;
            case I8 -> IrType.I8;
            case I16 -> IrType.I16;
            case U16 -> IrType.U16;
            case I32 -> IrType.I32;
            case I64 -> IrType.I64;
            case F32 -> IrType.F32;
            case F64 -> IrType.F64;
            default -> throw new IllegalArgumentException("not a primitive kind: " + kind);
        };
    }

    private record FunctionRequest(String baseLinkage, String specializedLinkage,
                                   Map<String, IrType> substitutions) {
    }

    private record ClassRequest(String rawName, String specializedName, IrType exactType,
                                int typeId) {
    }

    private record ClassContext(IrClass original, IrType exactType, String outputName,
                                int typeId) {
    }

    private record DispatchShape(int rawSlotIndex, List<IrType.Kind> callableKinds) {
        private DispatchShape {
            callableKinds = Collections.unmodifiableList(new ArrayList<>(callableKinds));
        }
    }
}
