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

/** Exact field values along single-predecessor paths, after all safety validation. */
final class FieldValueForwarder {
    private final Map<String, Leaf> leaves = new HashMap<>();

    private FieldValueForwarder(IrProgram program) {
        for (IrFunction function : program.functions()) {
            Leaf leaf = leaf(function);
            if (leaf != null) leaves.put(function.linkageName(), leaf);
        }
    }

    static IrProgram forward(IrProgram program) {
        if (program.entryPoint().isEmpty()) return program;
        var pass = new FieldValueForwarder(program);
        List<IrFunction> functions = program.functions().stream().map(pass::function).toList();
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(),
                program.typeInitializations(), program.arrayTypes(), program.stringConstants(),
                program.dispatchSlots(), functions, program.entryPoint().map(entry -> functions.stream()
                        .filter(f -> f.linkageName().equals(entry.linkageName())).findFirst().orElseThrow()),
                program.allocationFailure());
    }

    private IrFunction function(IrFunction function) {
        if (function.blocks().isEmpty()) return function;
        Map<String, IrBasicBlock> blocks = new LinkedHashMap<>();
        function.blocks().forEach(b -> blocks.put(b.label(), b));
        Map<String, Set<String>> predecessors = predecessors(function.blocks());
        Map<String, IrBasicBlock> rewritten = new HashMap<>();
        ArrayDeque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(function.blocks().getFirst().label(), new State()));
        while (!pending.isEmpty()) {
            Pending next = pending.removeFirst();
            if (rewritten.containsKey(next.label())) continue;
            IrBasicBlock block = blocks.get(next.label());
            State state = next.state();
            List<IrInstruction> instructions = new ArrayList<>();
            for (IrInstruction instruction : block.instructions()) instructions.add(step(instruction, state));
            IrTerminator end = block.terminator();
            if (end instanceof IrInvokeTerminator invoke) {
                IrInstruction replacement = step(invoke.call(), state);
                if (replacement != invoke.call()) {
                    instructions.add(replacement);
                    end = new IrJump(invoke.normalTarget(), invoke.sourceSpan());
                }
            }
            rewritten.put(block.label(), new IrBasicBlock(block.label(), instructions, end, block.sourceSpan()));
            for (String successor : successors(block.terminator())) {
                // Joins and unwind edges discard facts. A visited header is never
                // revisited with a backedge's values, even in irreducible CFGs.
                boolean normal = !(block.terminator() instanceof IrInvokeTerminator invoke)
                        || !successor.equals(invoke.unwindTarget());
                if (block.terminator() instanceof IrThrowTerminator) normal = false;
                pending.add(new Pending(successor, normal && predecessors.get(successor).size() == 1
                        ? state.copy() : new State()));
            }
        }
        List<IrBasicBlock> result = function.blocks().stream()
                .filter(b -> rewritten.containsKey(b.label())).map(b -> rewritten.get(b.label())).toList();
        result = reachable(result);
        return new IrFunction(function.ownerClass(), function.sourceName(), function.linkageName(),
                function.returnType(), function.parameters(), result, function.sourceSpan(),
                function.sourceFileName(), function.kind());
    }

    private IrInstruction step(IrInstruction instruction, State state) {
        if (instruction instanceof IrReferenceConversionInstruction copy) {
            state.copies.put(copy.result().id(), state.resolve(copy.value()));
        } else if (instruction instanceof IrFieldStoreInstruction store) {
            state.store(store.receiver(), store.field(), store.value());
        } else if (instruction instanceof IrFieldLoadInstruction load) {
            return state.load(instruction, load.result(), load.receiver(), load.field());
        } else if (instruction instanceof IrCallInstruction call) {
            Leaf leaf = leaves.get(call.targetLinkageName());
            if (leaf == null || call.arguments().size() != leaf.parameters().size()) {
                state.fields.clear();
            } else if (leaf.read() != null && call.result().isPresent()) {
                return state.load(instruction, call.result().orElseThrow(), call.arguments().getFirst(), leaf.read());
            } else {
                for (Write write : leaf.writes()) {
                    IrOperand value = write.value();
                    if (value instanceof IrValueReference reference) {
                        int index = leaf.parameters().indexOf(reference.id());
                        if (index < 0) throw new IllegalStateException("unresolved leaf parameter");
                        value = call.arguments().get(index);
                    }
                    state.store(call.arguments().getFirst(), write.field(), value);
                }
            }
        } else if (!transparent(instruction)) {
            // Unknown effects, initialization, native operations and reclamation
            // are barriers. Do not infer borrowing, freshness or disjointness.
            state.fields.clear();
        }
        return instruction;
    }

    private static boolean transparent(IrInstruction instruction) {
        return instruction instanceof IrBinaryInstruction || instruction instanceof IrUnaryInstruction
                || instruction instanceof IrNumericConversionInstruction || instruction instanceof IrPhiInstruction
                || instruction instanceof IrNullCheckInstruction || instruction instanceof IrArrayBoundsCheckInstruction
                || instruction instanceof IrArrayLengthCheckInstruction || instruction instanceof IrArrayLengthInstruction
                || instruction instanceof IrArrayLoadInstruction || instruction instanceof IrArrayStoreInstruction
                || instruction instanceof IrStaticFieldLoadInstruction || instruction instanceof IrStaticFieldStoreInstruction
                || instruction instanceof IrTypeInitializedInstruction || instruction instanceof IrInstanceOfInstruction
                || instruction instanceof IrArrayTypeTestInstruction || instruction instanceof IrAllocateInstruction
                || instruction instanceof IrArrayAllocateInstruction;
    }

    private static boolean supported(IrType type) {
        return type.equals(IrType.I1) || type.isIntegral() || type.isReference();
    }

    private static final class State {
        private final Map<Key, IrOperand> fields = new HashMap<>();
        private final Map<Integer, IrOperand> copies = new HashMap<>();

        State copy() {
            State result = new State();
            result.fields.putAll(fields);
            result.copies.putAll(copies);
            return result;
        }

        IrOperand resolve(IrOperand value) {
            return value instanceof IrValueReference reference ? copies.getOrDefault(reference.id(), value) : value;
        }

        Key key(IrOperand receiver, IrField field) {
            IrOperand exact = resolve(receiver);
            // SSA uses can carry different spans. The id, not record equality,
            // establishes receiver identity within this function.
            Object identity = exact instanceof IrValueReference reference ? reference.id() : exact;
            return new Key(identity, new Storage(field.ownerClass(), field.layoutIndex()));
        }

        void store(IrOperand receiver, IrField field, IrOperand value) {
            Key key = key(receiver, field);
            fields.keySet().removeIf(k -> k.storage().equals(key.storage()));
            if (supported(field.type())) fields.put(key, resolve(value));
        }

        IrInstruction load(IrInstruction original, IrValueReference result, IrOperand receiver, IrField field) {
            if (!supported(field.type())) return original;
            Key key = key(receiver, field);
            IrOperand value = fields.get(key);
            if (value == null || !value.type().equals(result.type())) {
                fields.put(key, result);
                return original;
            }
            if (result.type().isReference()) {
                copies.put(result.id(), resolve(value));
                return new IrReferenceConversionInstruction(result, value, original.sourceSpan());
            }
            return new IrBinaryInstruction(result, IrBinaryOperator.ADD, value,
                    new IrConstant(result.type(), 0, original.sourceSpan()), original.sourceSpan());
        }
    }

    /** Only leaf getters/setters with one nonnull receiver path qualify. */
    private static Leaf leaf(IrFunction function) {
        if (function.kind() != IrCallableKind.METHOD || function.parameters().isEmpty()
                || !function.parameters().getFirst().value().type().isReference()
                || function.blocks().isEmpty()
                || function.blocks().stream().mapToInt(b -> b.instructions().size() + 1).sum() > 64) return null;
        Map<String, IrBasicBlock> blocks = new HashMap<>();
        function.blocks().forEach(b -> blocks.put(b.label(), b));
        Map<Integer, IrOperand> copies = new HashMap<>();
        int receiver = function.parameters().getFirst().value().id();
        Set<Integer> checks = new HashSet<>();
        List<Write> writes = new ArrayList<>();
        IrField read = null;
        int readId = -1;
        Set<String> visited = new HashSet<>();
        IrBasicBlock block = function.blocks().getFirst();
        List<Integer> parameters = function.parameters().stream().map(p -> p.value().id()).toList();
        while (block != null && visited.add(block.label())) {
            for (IrInstruction instruction : block.instructions()) {
                if (instruction instanceof IrReferenceConversionInstruction copy) {
                    IrOperand value = resolve(copy.value(), copies);
                    if (!(value instanceof IrValueReference reference) || !parameters.contains(reference.id())) return null;
                    copies.put(copy.result().id(), value);
                } else if (instruction instanceof IrNullCheckInstruction check
                        && isReceiver(resolve(check.receiver(), copies), receiver)) {
                    checks.add(check.result().id());
                } else if (instruction instanceof IrFieldLoadInstruction load && read == null && writes.isEmpty()
                        && isReceiver(resolve(load.receiver(), copies), receiver)) {
                    read = load.field();
                    readId = load.result().id();
                } else if (instruction instanceof IrFieldStoreInstruction store && read == null
                        && isReceiver(resolve(store.receiver(), copies), receiver)) {
                    IrOperand value = resolve(store.value(), copies);
                    if (value instanceof IrValueReference reference && !parameters.contains(reference.id())) return null;
                    writes.add(new Write(store.field(), value));
                } else {
                    return null;
                }
            }
            if (block.terminator() instanceof IrReturnTerminator end) {
                if (read != null && end.value().orElse(null) instanceof IrValueReference value
                        && value.id() == readId && function.parameters().size() == 1)
                    return new Leaf(parameters, read, List.of());
                return read == null && end.value().isEmpty() ? new Leaf(parameters, null, List.copyOf(writes)) : null;
            }
            if (block.terminator() instanceof IrJump jump) {
                block = blocks.get(jump.target());
            } else if (block.terminator() instanceof IrBranch branch
                    && branch.condition() instanceof IrValueReference condition && checks.contains(condition.id())
                    && cannotReturn(branch.falseTarget(), blocks)) {
                block = blocks.get(branch.trueTarget());
            } else {
                return null;
            }
        }
        return null;
    }

    private static IrOperand resolve(IrOperand operand, Map<Integer, IrOperand> copies) {
        return operand instanceof IrValueReference reference ? copies.getOrDefault(reference.id(), operand) : operand;
    }

    private static boolean isReceiver(IrOperand operand, int id) {
        return operand instanceof IrValueReference reference && reference.id() == id;
    }

    private static boolean cannotReturn(String label, Map<String, IrBasicBlock> blocks) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(label);
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (!visited.add(next)) continue;
            IrTerminator end = blocks.get(next).terminator();
            if (end instanceof IrReturnTerminator) return false;
            queue.addAll(successors(end));
        }
        return true;
    }

    private static List<String> successors(IrTerminator end) {
        return switch (end) {
            case IrJump t -> List.of(t.target());
            case IrBranch t -> List.of(t.trueTarget(), t.falseTarget());
            case IrSwitchTerminator t -> Stream.concat(Stream.of(t.defaultTarget()), t.cases().stream().map(IrSwitchCase::target)).distinct().toList();
            case IrInvokeTerminator t -> List.of(t.normalTarget(), t.unwindTarget());
            // A plain throw's nominal normal label is not a CFG successor.
            case IrThrowTerminator t -> t.unwindTarget().map(u -> List.of(t.normalTarget(), u)).orElse(List.of());
            default -> List.of();
        };
    }

    private static Map<String, Set<String>> predecessors(List<IrBasicBlock> blocks) {
        Map<String, Set<String>> result = new HashMap<>();
        blocks.forEach(b -> result.put(b.label(), new HashSet<>()));
        blocks.forEach(b -> successors(b.terminator()).forEach(s -> result.get(s).add(b.label())));
        return result;
    }

    private static List<IrBasicBlock> reachable(List<IrBasicBlock> blocks) {
        Map<String, IrBasicBlock> byLabel = new HashMap<>();
        blocks.forEach(b -> byLabel.put(b.label(), b));
        Set<String> reached = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(blocks.getFirst().label());
        while (!queue.isEmpty()) {
            String label = queue.removeFirst();
            if (reached.add(label)) queue.addAll(successors(byLabel.get(label).terminator()));
        }
        List<IrBasicBlock> live = blocks.stream().filter(b -> reached.contains(b.label())).toList();
        Map<String, Set<String>> predecessors = predecessors(live);
        return live.stream().map(b -> new IrBasicBlock(b.label(), b.instructions().stream().map(i ->
                i instanceof IrPhiInstruction phi ? (IrInstruction) new IrPhiInstruction(phi.result(),
                        phi.incoming().stream().filter(p -> predecessors.get(b.label()).contains(p.predecessor())).toList(),
                        phi.sourceSpan()) : i).toList(), b.terminator(), b.sourceSpan())).toList();
    }

    private record Storage(String owner, int index) {}
    private record Key(Object receiver, Storage storage) {}
    private record Write(IrField field, IrOperand value) {}
    private record Leaf(List<Integer> parameters, IrField read, List<Write> writes) {}
    private record Pending(String label, State state) {}
}
