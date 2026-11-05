// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import ironwood.compiler.ir.*;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;

/** Explicit development admission, never a production memory-model option. */
public final class GcExperimentalClosure {
    private GcExperimentalClosure() {}

    public static GcEffects validate(IrProgram program) {
        if (program.entryPoint().isPresent() || program.allocationFailure().isPresent()
                || !program.stringConstants().isEmpty() || program.exportRoots().isEmpty()) {
            throw new IllegalArgumentException("GC3 requires explicit standalone roots without legacy entry/exception owners");
        }
        // Inventory the entire selected program, including refused families.
        IrAllocationLayout.of(program).forEach(IrAllocationLayout::requireSupported);
        var classes = new HashSet<String>();
        program.classes().forEach(type -> classes.add(type.name()));
        program.arrayTypes().forEach(type -> classes.add(type.name()));
        for (var type : program.classes()) {
            if (type.destructorChain().isPresent() || type.constructorRollback().isPresent()) {
                throw new IllegalArgumentException("GC3 refuses legacy destruction/rollback owners: " + type.name());
            }
            type.fields().forEach(field -> requireType(field.type(), classes));
        }
        for (var field : program.staticFields()) {
            requireType(field.type(), classes);
            if (!(field.initialValue() instanceof IrNull || field.initialValue() instanceof IrConstant)) {
                throw new IllegalArgumentException("GC3 refuses undescribed immortal static storage");
            }
        }
        var effects = GcEffects.analyze(program);
        for (var function : program.functions()) {
            requireType(function.returnType(), classes);
            function.parameters().forEach(parameter -> requireType(parameter.value().type(), classes));
            if (function.returnType().equals(IrType.EXCEPTION)
                    || function.parameters().stream().anyMatch(p -> p.value().type().equals(IrType.EXCEPTION))) {
                throw new IllegalArgumentException("GC3 refuses unqualified native pointer signatures");
            }
            if (effects.function(function.linkageName()).unknown()) {
                throw new IllegalArgumentException("GC3 refuses unknown collection/native/exception effects: " + function.linkageName());
            }
            Set<Integer> slots = new HashSet<>();
            var projections = new HashMap<Integer, IrGcProjectionInstruction>();
            function.blocks().forEach(block -> block.instructions().forEach(instruction -> {
                if (instruction instanceof IrGcRootSlotInstruction slot) slots.add(slot.slot().id());
                if (instruction instanceof IrGcProjectionInstruction projection
                        && projections.putIfAbsent(projection.result().id(), projection) != null) {
                    throw new IllegalArgumentException("GC3 duplicate projection identity");
                }
            }));
            var scanner = new IrCfgRenamer(value -> { requireType(value.type(), classes); return value; }, label -> label);
            function.blocks().forEach(scanner::block);
            for (var block : function.blocks()) {
                if (block.terminator() instanceof IrThrowTerminator || block.terminator() instanceof IrInvokeTerminator invoke
                        && !(invoke.call() instanceof IrGcTestExceptionInstruction test
                        && test.operation() == IrGcTestExceptionInstruction.Operation.RAISE_SCALAR)) {
                    throw new IllegalArgumentException("GC3 standalone closure refuses unqualified exceptional crossings");
                }
                for (var instruction : GcEffects.operations(block)) {
                    if (!supported(instruction)) {
                        throw new IllegalArgumentException("GC3 unsupported root/lowering owner: " + instruction.getClass().getSimpleName());
                    }
                    if (instruction instanceof IrGcRootLoadInstruction load && !slots.contains(load.slot().id())) {
                        throw new IllegalArgumentException("GC3 load from undescribed stack region");
                    }
                    if (instruction instanceof IrGcProjectionInstruction projection) {
                        if (!projection.base().type().isReference() || !projection.result().type().equals(projection.base().type())
                                || projection.base() instanceof IrValueReference base && projections.containsKey(base.id())
                                || program.classes().stream().noneMatch(type -> type.name().equals(projection.base().type().erasure().referenceName())
                                && type.fields().contains(projection.field()))) {
                            throw new IllegalArgumentException("GC3 invalid physical field projection");
                        }
                    } else if (instruction instanceof IrGcReadProjectionInstruction load) {
                        var projection = projections.get(load.address().id());
                        if (projection == null || !projection.field().type().equals(load.result().type())) {
                            throw new IllegalArgumentException("GC3 untyped projection load");
                        }
                    } else {
                        rejectProjectionUse(instruction, projections.keySet());
                    }
                    if (instruction instanceof IrGcTestExceptionInstruction test
                            && test.operation() == IrGcTestExceptionInstruction.Operation.CATCH_SCALAR
                            && (block.instructions().isEmpty() || block.instructions().getFirst() != instruction)) {
                        throw new IllegalArgumentException("GC3 native test catch must start an unwind block");
                    }
                }
                new IrCfgRenamer(value -> {
                    if (projections.containsKey(value.id())) throw new IllegalArgumentException("GC3 derived address escapes its qualified field load");
                    return value;
                }, label -> label).terminator(block.terminator());
            }
        }
        for (var initialization : program.typeInitializations()) {
            if (effects.initialization(initialization.typeName()).unknown()) {
                throw new IllegalArgumentException("GC3 unresolved initialization prerequisites");
            }
        }
        return effects;
    }

    private static void rejectProjectionUse(IrInstruction instruction, Set<Integer> projections) {
        new IrCfgRenamer(value -> {
            if (projections.contains(value.id())) throw new IllegalArgumentException("GC3 derived address used as an allocation base");
            return value;
        }, label -> label).instruction(instruction);
    }

    private static void requireType(IrType type, Set<String> classes) {
        type = type.erasure();
        if (type.isReference() && !classes.contains(type.displayName())) {
            throw new IllegalArgumentException("GC3 reference outside selected layout closure: " + type.displayName());
        }
    }

    private static boolean supported(IrInstruction instruction) {
        return instruction instanceof IrGcCollectInstruction || instruction instanceof IrGcRootSlotInstruction
                || instruction instanceof IrGcProjectionInstruction || instruction instanceof IrGcReadProjectionInstruction
                || instruction instanceof IrGcTestExceptionInstruction
                || instruction instanceof IrGcRootLoadInstruction || instruction instanceof IrAllocateInstruction
                || instruction instanceof IrArrayAllocateInstruction || instruction instanceof IrCallInstruction
                || instruction instanceof IrVirtualCallInstruction || instruction instanceof IrInterfaceCallInstruction
                || instruction instanceof IrEnsureTypeInitializedInstruction || instruction instanceof IrTypeInitializedInstruction
                || instruction instanceof IrFieldLoadInstruction || instruction instanceof IrFieldStoreInstruction
                || instruction instanceof IrStaticFieldLoadInstruction || instruction instanceof IrStaticFieldStoreInstruction
                || instruction instanceof IrReferenceConversionInstruction || instruction instanceof IrNumericConversionInstruction
                || instruction instanceof IrPhiInstruction || instruction instanceof IrBinaryInstruction
                || instruction instanceof IrUnaryInstruction || instruction instanceof IrNullCheckInstruction
                || instruction instanceof IrArrayLengthInstruction || instruction instanceof IrArrayLoadInstruction
                || instruction instanceof IrArrayStoreInstruction || instruction instanceof IrArrayBoundsCheckInstruction
                || instruction instanceof IrArrayLengthCheckInstruction;
    }
}
