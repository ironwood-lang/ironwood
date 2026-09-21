// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Bounded direct-call specialization of existing constant enum SSA identities. */
final class EnumArgumentSpecializer {
    private static final String SUFFIX = ".$enumarg.";
    private final IrProgram program;
    private final Map<String, IrFunction> functions = new LinkedHashMap<>();
    private final Map<Key, IrFunction> clones = new LinkedHashMap<>();
    private final Map<String, Boolean> recursive = new HashMap<>();
    private int remaining = 2048;

    private EnumArgumentSpecializer(IrProgram program) {
        this.program = program;
        program.functions().forEach(f -> functions.put(f.linkageName(), f));
    }

    static IrProgram specialize(IrProgram program) {
        if (program.functions().stream().anyMatch(f -> f.linkageName().contains(SUFFIX))) return program;
        return new EnumArgumentSpecializer(program).run();
    }

    private IrProgram run() {
        Map<String, IrFunction> rewritten = new LinkedHashMap<>();
        for (IrFunction function : program.functions()) {
            Map<Integer, IrEnumConstant> constants = new HashMap<>();
            // Only reference-copy chains introduce facts. Loads, joins and calls
            // cannot turn mutable object state into a constant identity.
            boolean changed;
            do {
                changed = false;
                for (IrInstruction instruction : operations(function).toList()) {
                    if (instruction instanceof IrReferenceConversionInstruction copy) {
                        IrEnumConstant value = constant(copy.value(), constants);
                        if (value != null && constants.putIfAbsent(copy.result().id(), value) == null) changed = true;
                    }
                }
            } while (changed);
            List<IrBasicBlock> blocks = new ArrayList<>();
            for (IrBasicBlock block : function.blocks()) {
                List<IrInstruction> instructions = block.instructions().stream()
                        .map(i -> redirect(i, constants)).toList();
                IrTerminator end = block.terminator();
                if (end instanceof IrInvokeTerminator invoke) {
                    end = new IrInvokeTerminator(redirect(invoke.call(), constants), invoke.normalTarget(),
                            invoke.unwindTarget(), invoke.sourceSpan());
                }
                blocks.add(new IrBasicBlock(block.label(), instructions, end, block.sourceSpan()));
            }
            rewritten.put(function.linkageName(), copy(function, function.linkageName(), blocks));
        }
        if (clones.isEmpty()) return program;
        List<IrFunction> result = new ArrayList<>(rewritten.values());
        result.addAll(clones.values());
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(),
                program.typeInitializations(), program.arrayTypes(), program.stringConstants(),
                program.dispatchSlots(), result, program.entryPoint().map(f -> rewritten.get(f.linkageName())),
                program.allocationFailure());
    }

    private IrInstruction redirect(IrInstruction instruction, Map<Integer, IrEnumConstant> constants) {
        if (!(instruction instanceof IrCallInstruction call)) return instruction;
        IrFunction target = functions.get(call.targetLinkageName());
        if (target == null || target.kind() != IrCallableKind.METHOD || cost(target) > 256
                || recursive.computeIfAbsent(target.linkageName(), this::isRecursive)) return instruction;
        for (int index = 0; index < call.arguments().size(); index++) {
            IrEnumConstant value = constant(call.arguments().get(index), constants);
            if (value == null || !target.parameters().get(index).value().type().isReference()) continue;
            Key key = new Key(target.linkageName(), index, value.symbol());
            IrFunction clone = clones.get(key);
            if (clone == null) {
                if (clones.size() >= 32 || cost(target) + 1 > remaining
                        || clones.keySet().stream().filter(k -> k.target().equals(target.linkageName())).count() >= 2) {
                    return instruction;
                }
                clone = specialize(target, index, value, target.linkageName() + SUFFIX + clones.size());
                clones.put(key, clone);
                remaining -= cost(target) + 1;
            }
            // Retain the full signature and all evaluated arguments. Only body
            // uses of one parameter change; call/invoke timing is identical.
            return new IrCallInstruction(call.result(), clone.linkageName(), call.returnType(), call.arguments(),
                    call.callKind(), call.devirtualizedFrom(), call.specializationArguments(), call.sourceSpan());
        }
        return instruction;
    }

    private boolean isRecursive(String name) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>(callees(functions.get(name)));
        while (!work.isEmpty()) {
            String current = work.removeFirst();
            if (current.equals(name)) return true;
            if (seen.add(current) && functions.containsKey(current)) work.addAll(callees(functions.get(current)));
        }
        return false;
    }

    private static IrFunction specialize(IrFunction function, int index, IrEnumConstant value, String name) {
        int[] next = {0};
        function.parameters().forEach(p -> next[0] = Math.max(next[0], p.value().id() + 1));
        var scanner = new IrCfgRenamer(v -> { next[0] = Math.max(next[0], v.id() + 1); return v; }, l -> l);
        function.blocks().forEach(scanner::block);
        IrValueReference parameter = function.parameters().get(index).value();
        IrValueReference replacement = new IrValueReference(next[0], parameter.type(), parameter.sourceSpan());
        var renamer = new IrCfgRenamer(v -> v.id() == parameter.id() ? replacement : v, l -> l);
        List<IrBasicBlock> blocks = new ArrayList<>(function.blocks().stream().map(renamer::block).toList());
        IrBasicBlock first = blocks.getFirst();
        List<IrInstruction> instructions = new ArrayList<>();
        instructions.add(new IrReferenceConversionInstruction(replacement, value, parameter.sourceSpan()));
        instructions.addAll(first.instructions());
        blocks.set(0, new IrBasicBlock(first.label(), instructions, first.terminator(), first.sourceSpan()));
        return copy(function, name, blocks);
    }

    private static IrEnumConstant constant(IrOperand value, Map<Integer, IrEnumConstant> constants) {
        return value instanceof IrEnumConstant c ? c
                : value instanceof IrValueReference reference ? constants.get(reference.id()) : null;
    }

    private static IrFunction copy(IrFunction function, String name, List<IrBasicBlock> blocks) {
        return new IrFunction(function.ownerClass(), function.sourceName(), name, function.returnType(),
                function.parameters(), blocks, function.sourceSpan(), function.sourceFileName(), function.kind());
    }

    private static int cost(IrFunction function) {
        return function.blocks().stream().mapToInt(b -> b.instructions().size() + 1).sum();
    }

    private static List<String> callees(IrFunction function) {
        return operations(function).filter(IrCallInstruction.class::isInstance)
                .map(IrCallInstruction.class::cast).map(IrCallInstruction::targetLinkageName).toList();
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(b -> Stream.concat(b.instructions().stream(),
                b.terminator() instanceof IrInvokeTerminator i ? Stream.of(i.call()) : Stream.empty()));
    }

    private record Key(String target, int parameter, String symbol) {}
}
