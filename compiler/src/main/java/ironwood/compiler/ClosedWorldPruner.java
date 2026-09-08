// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.IrStreamInstruction;
import ironwood.compiler.ir.IrAllocateInstruction;
import ironwood.compiler.ir.IrArrayAllocateInstruction;
import ironwood.compiler.ir.IrArrayType;
import ironwood.compiler.ir.IrCallInstruction;
import ironwood.compiler.ir.IrClass;
import ironwood.compiler.ir.IrField;
import ironwood.compiler.ir.IrDispatchSlot;
import ironwood.compiler.ir.IrFieldLoadInstruction;
import ironwood.compiler.ir.IrFieldStoreInstruction;
import ironwood.compiler.ir.IrFileInstruction;
import ironwood.compiler.ir.IrEnsureTypeInitializedInstruction;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrInstanceOfInstruction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrInterfaceCallInstruction;
import ironwood.compiler.ir.IrInvokeTerminator;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrObjectToStringInstruction;
import ironwood.compiler.ir.IrThrowableDescriptionInstruction;
import ironwood.compiler.ir.IrStaticField;
import ironwood.compiler.ir.IrStaticFieldLoadInstruction;
import ironwood.compiler.ir.IrStaticFieldStoreInstruction;
import ironwood.compiler.ir.IrStringConstant;
import ironwood.compiler.ir.IrStringConcatInstruction;
import ironwood.compiler.ir.IrStringCopyInstruction;
import ironwood.compiler.ir.IrStringFromCharRangeInstruction;
import ironwood.compiler.ir.IrStringFromUtf8Instruction;
import ironwood.compiler.ir.IrStringJoinInstruction;
import ironwood.compiler.ir.IrStringReplaceTextInstruction;
import ironwood.compiler.ir.IrStringReplaceCharInstruction;
import ironwood.compiler.ir.IrStringRepeatInstruction;
import ironwood.compiler.ir.IrStringCaseInstruction;
import ironwood.compiler.ir.IrStringFromIntegerInstruction;
import ironwood.compiler.ir.IrStringFromCharacterInstruction;
import ironwood.compiler.ir.IrStringFromCharsInstruction;
import ironwood.compiler.ir.IrStringFromRangeInstruction;
import ironwood.compiler.ir.IrSystemGetenvInstruction;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrTypeInitialization;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Removes declarations unreachable from the selected closed-world entry function. */
final class ClosedWorldPruner {
    private final IrProgram program;
    private final Map<String, IrClass> classes = new LinkedHashMap<>();
    private final Map<String, IrFunction> functions = new LinkedHashMap<>();
    private final Map<String, IrTypeInitialization> typeInitializations = new LinkedHashMap<>();
    private final Set<String> reachableClasses = new LinkedHashSet<>();
    private final Set<String> reachableFunctions = new LinkedHashSet<>();
    private final Set<Integer> reachableDispatchSlots = new LinkedHashSet<>();
    private final Set<IrStaticField> reachableStaticFields = new LinkedHashSet<>();
    private final Set<IrType> reachableArrayTypes = new LinkedHashSet<>();
    private final Set<IrStringConstant> reachableStringConstants = new LinkedHashSet<>();
    private final Map<IrType, IrArrayType> arrayTypes = new LinkedHashMap<>();
    private final ArrayDeque<String> classWork = new ArrayDeque<>();
    private final ArrayDeque<String> functionWork = new ArrayDeque<>();
    private boolean allocationFailureReachable;

    private ClosedWorldPruner(IrProgram program) {
        this.program = program;
        program.classes().forEach(type -> classes.put(type.name(), type));
        program.functions().forEach(function -> functions.put(function.linkageName(), function));
        program.typeInitializations().forEach(initialization ->
                typeInitializations.put(initialization.typeName(), initialization));
        program.arrayTypes().forEach(type -> arrayTypes.put(type.type(), type));
    }

    static IrProgram prune(IrProgram program) {
        if (program.entryPoint().isEmpty()) {
            return program;
        }
        return new ClosedWorldPruner(program).run();
    }

    private IrProgram run() {
        enqueueFunction(program.entryPoint().orElseThrow().linkageName());
        while (!functionWork.isEmpty() || !classWork.isEmpty()) {
            while (!functionWork.isEmpty()) {
                scanFunction(functions.get(functionWork.removeFirst()));
            }
            while (!classWork.isEmpty()) {
                scanClass(classes.get(classWork.removeFirst()));
            }
        }
        List<IrClass> keptClasses = program.classes().stream()
                .filter(type -> reachableClasses.contains(type.name()))
                .map(this::pruneDispatchEntries).toList();
        List<IrFunction> keptFunctions = program.functions().stream()
                .filter(function -> reachableFunctions.contains(function.linkageName())).toList();
        List<IrStaticField> keptStatics = program.staticFields().stream()
                .filter(reachableStaticFields::contains).toList();
        List<IrTypeInitialization> keptInitializations = program.typeInitializations().stream()
                .filter(initialization -> reachableClasses.contains(initialization.typeName())).toList();
        List<IrArrayType> keptArrays = program.arrayTypes().stream()
                .filter(type -> reachableArrayTypes.contains(type.type()))
                .map(type -> new IrArrayType(type.type(), type.typeId(), type.typeMembership(),
                        type.dispatchEntries().stream().filter(entry ->
                                reachableDispatchSlots.contains(entry.slot().index())).toList(),
                        type.toStringReturnsOwnedFresh())).toList();
        List<IrStringConstant> keptStrings = program.stringConstants().stream()
                .filter(reachableStringConstants::contains).toList();
        IrFunction entry = keptFunctions.stream()
                .filter(function -> function.linkageName().equals(
                        program.entryPoint().orElseThrow().linkageName()))
                .findFirst().orElseThrow();
        return new IrProgram(program.moduleName(), keptClasses, keptStatics, keptInitializations,
                keptArrays, keptStrings, program.dispatchSlots(),
                keptFunctions, Optional.of(entry), allocationFailureReachable
                ? program.allocationFailure() : Optional.empty());
    }

    private IrClass pruneDispatchEntries(IrClass type) {
        return new IrClass(type.name(), type.kind(), type.superclass(), type.interfaces(),
                type.fields(), type.typeId(), type.typeMembership(),
                type.dispatchEntries().stream().filter(entry ->
                        reachableDispatchSlots.contains(entry.slot().index())).toList(),
                type.destructorChain(), type.constructorRollback(), type.toStringReturnsOwnedFresh(),
                type.localizedMessageReturnsOwnedFresh(), type.sourceSpan());
    }

    private void enqueueDispatchSlot(int index) {
        if (!reachableDispatchSlots.add(index)) return;
        // A call discovered after its receiver type must still retain that type's
        // override. Preserve slot indices; unobserved entries become null metadata.
        for (String name : reachableClasses) {
            classes.get(name).dispatchEntries().stream()
                    .filter(entry -> entry.slot().index() == index)
                    .forEach(entry -> enqueueFunction(entry.targetLinkageName()));
        }
        for (IrType type : reachableArrayTypes) {
            IrArrayType metadata = arrayTypes.get(type);
            if (metadata != null) metadata.dispatchEntries().stream()
                    .filter(entry -> entry.slot().index() == index)
                    .forEach(entry -> enqueueFunction(entry.targetLinkageName()));
        }
    }

    private void scanFunction(IrFunction function) {
        if (function == null) {
            return;
        }
        enqueueClass(function.ownerClass());
        scanType(function.returnType());
        function.parameters().forEach(parameter -> scanType(parameter.value().type()));
        function.blocks().forEach(block -> {
            block.instructions().forEach(this::scanInstruction);
            scanRecord(block.terminator());
            if (block.terminator() instanceof IrInvokeTerminator invoke) {
                scanInstruction(invoke.call());
            }
        });
    }

    private void scanClass(IrClass type) {
        if (type == null) {
            return;
        }
        type.superclass().ifPresent(this::enqueueClass);
        type.interfaces().forEach(this::enqueueClass);
        type.fields().forEach(field -> scanType(field.type()));
        type.dispatchEntries().stream()
                .filter(entry -> reachableDispatchSlots.contains(entry.slot().index()))
                .forEach(entry -> enqueueFunction(entry.targetLinkageName()));
        type.destructorChain().ifPresent(this::enqueueFunction);
        type.constructorRollback().ifPresent(this::enqueueFunction);
        IrTypeInitialization initialization = typeInitializations.get(type.name());
        if (initialization != null) {
            initialization.prerequisiteTypes().forEach(this::enqueueClass);
            initialization.initializerLinkageName().ifPresent(this::enqueueFunction);
        }
    }

    private void scanInstruction(IrInstruction instruction) {
        scanRecord(instruction);
        if (instruction instanceof IrCallInstruction call) {
            enqueueFunction(call.targetLinkageName());
        } else if (instruction instanceof IrEnsureTypeInitializedInstruction ensure) {
            enqueueClass(ensure.typeName());
        } else if (instruction instanceof IrAllocateInstruction allocation) {
            enqueueClass(allocation.className());
        } else if (instruction instanceof IrInstanceOfInstruction typeTest) {
            enqueueClass(typeTest.targetTypeName());
        } else if (instruction instanceof IrInterfaceCallInstruction interfaceCall) {
            enqueueClass(interfaceCall.interfaceName());
        } else if (instruction instanceof IrFieldLoadInstruction fieldLoad) {
            scanField(fieldLoad.field());
        } else if (instruction instanceof IrFieldStoreInstruction fieldStore) {
            scanField(fieldStore.field());
        } else if (instruction instanceof IrStaticFieldLoadInstruction staticLoad) {
            scanStaticField(staticLoad.field());
        } else if (instruction instanceof IrStaticFieldStoreInstruction staticStore) {
            scanStaticField(staticStore.field());
        }
        if (instruction instanceof IrAllocateInstruction
                || instruction instanceof IrArrayAllocateInstruction
                || instruction instanceof IrObjectToStringInstruction
                || instruction instanceof IrThrowableDescriptionInstruction
                || instruction instanceof IrStringCopyInstruction
                || instruction instanceof IrStringFromCharsInstruction
                || instruction instanceof IrStringCaseInstruction
                || instruction instanceof IrStringRepeatInstruction
                || instruction instanceof IrStringReplaceCharInstruction
                || instruction instanceof IrStringReplaceTextInstruction
                || instruction instanceof IrStringJoinInstruction
                || instruction instanceof IrStringFromUtf8Instruction
                || instruction instanceof IrStringFromCharRangeInstruction
                || instruction instanceof IrStringFromRangeInstruction
                || instruction instanceof IrStringFromIntegerInstruction
                || instruction instanceof IrStringFromCharacterInstruction
                || instruction instanceof IrStringConcatInstruction
                || instruction instanceof IrSystemGetenvInstruction
                || instruction instanceof IrFileInstruction
                || instruction instanceof IrStreamInstruction) {
            allocationFailureReachable = true;
            program.allocationFailure().ifPresent(error ->
                    enqueueClass(error.type().referenceName()));
        }
    }

    private void scanField(IrField field) {
        enqueueClass(field.ownerClass());
        scanType(field.type());
    }

    private void scanStaticField(IrStaticField field) {
        if (reachableStaticFields.add(field)) {
            enqueueClass(field.ownerClass());
            scanType(field.type());
            scanRecord(field.initialValue());
        }
    }

    private void scanType(IrType type) {
        if (type == null) {
            return;
        }
        if (type.isArray()) {
            IrType erased = type.erasure();
            if (reachableArrayTypes.add(erased)) {
                IrArrayType metadata = arrayTypes.get(erased);
                if (metadata != null) {
                    metadata.dispatchEntries().stream()
                            .filter(entry -> reachableDispatchSlots.contains(entry.slot().index()))
                            .forEach(entry -> enqueueFunction(entry.targetLinkageName()));
                }
            }
            scanType(type.elementType());
        } else if (type.isNominalReference()) {
            enqueueClass(type.referenceName());
            type.typeArguments().forEach(this::scanType);
        }
    }

    private void enqueueClass(String name) {
        if (classes.containsKey(name) && reachableClasses.add(name)) {
            classWork.addLast(name);
        }
    }

    private void enqueueFunction(String linkageName) {
        if (functions.containsKey(linkageName) && reachableFunctions.add(linkageName)) {
            functionWork.addLast(linkageName);
        }
    }

    private void scanRecord(Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof IrDispatchSlot slot) {
            enqueueDispatchSlot(slot.index());
            return;
        }
        if (value instanceof IrType type) {
            scanType(type);
            return;
        }
        if (value instanceof IrStringConstant string) {
            reachableStringConstants.add(string);
            scanType(string.type());
            return;
        }
        if (value instanceof IrOperand operand) {
            scanType(operand.type());
        }
        if (value instanceof IrField field) {
            scanField(field);
            return;
        }
        if (value instanceof IrStaticField field) {
            scanStaticField(field);
            return;
        }
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(this::scanRecord);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(this::scanRecord);
            return;
        }
        Class<?> valueClass = value.getClass();
        if (!valueClass.isRecord() || !valueClass.getPackageName().startsWith("ironwood.compiler.ir")) {
            return;
        }
        for (var component : valueClass.getRecordComponents()) {
            try {
                scanRecord(component.getAccessor().invoke(value));
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalStateException("cannot inspect typed IR reachability", exception);
            }
        }
    }
}
