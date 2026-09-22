// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Removes unobserved primitive stores after validation and closed-world pruning. */
final class UnreadFieldStoreEliminator {
    // These layouts are observed directly by the C runtime, outside typed loads.
    private static final Set<String> RUNTIME_LAYOUTS = Set.of(
            "ironwood.lang.String", "ironwood.lang.Throwable", "ironwood.io.PrintStream");

    private UnreadFieldStoreEliminator() {}

    static IrProgram eliminate(IrProgram program) {
        if (program.entryPoint().isEmpty()) return program;
        Set<Storage> observed = new HashSet<>();
        for (IrFunction function : program.functions()) {
            operations(function).forEach(instruction -> {
                if (instruction instanceof IrFieldLoadInstruction load) {
                    observed.add(Storage.of(load.field()));
                } else if (instruction instanceof IrTcpInstruction tcp) {
                    // Field addresses passed to native code are not private stores.
                    tcp.outputFields().forEach(field -> observed.add(Storage.of(field)));
                }
            });
        }
        Set<Storage> removable = new HashSet<>();
        program.classes().forEach(type -> type.fields().stream()
                .filter(field -> field.type().isPrimitive()
                        && !RUNTIME_LAYOUTS.contains(field.ownerClass()))
                .map(Storage::of).filter(field -> !observed.contains(field)).forEach(removable::add));
        if (removable.isEmpty()) return program;
        List<IrFunction> functions = program.functions().stream().map(function -> {
            List<IrBasicBlock> blocks = function.blocks().stream().map(block -> new IrBasicBlock(
                    block.label(), block.instructions().stream().filter(instruction ->
                            !(instruction instanceof IrFieldStoreInstruction store)
                                    || !removable.contains(Storage.of(store.field()))).toList(),
                    block.terminator(), block.sourceSpan())).toList();
            return new IrFunction(function.ownerClass(), function.sourceName(), function.linkageName(),
                    function.returnType(), function.parameters(), blocks, function.sourceSpan(),
                    function.sourceFileName(), function.kind());
        }).toList();
        // Checks and RHS evaluation are separate typed operations. Keep all of
        // them, including invokes, and retain layouts and reference stores so
        // this pass cannot affect allocation or reclamation contracts.
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(),
                program.typeInitializations(), program.arrayTypes(), program.stringConstants(),
                program.dispatchSlots(), functions, program.entryPoint().map(entry -> functions.stream()
                        .filter(function -> function.linkageName().equals(entry.linkageName()))
                        .findFirst().orElseThrow()), program.allocationFailure());
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(block -> Stream.concat(block.instructions().stream(),
                block.terminator() instanceof IrInvokeTerminator invoke
                        ? Stream.of(invoke.call()) : Stream.empty()));
    }

    private record Storage(String owner, int index) {
        static Storage of(IrField field) {
            return new Storage(field.ownerClass(), field.layoutIndex());
        }
    }
}
