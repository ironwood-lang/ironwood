// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeOwnedCallbackAdmission;
import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/** Optional scheduling proof after mandatory ownership and exception admission. */
public final class BridgeCallbackBatching {
    public static final int CAPACITY = 1024;
    public record Entry(BridgeCallableId callable, int countInput, String entrySymbol,
                        String callbackSymbol, String listener, String method, int arity, int index) {
        public String relay() { return "batch" + index; }
        public String methodField() { return "iw_callback_batch_method_" + index; }
        public String descriptor() { return "(L" + listener.replace('.', '/') + ";Ljava/nio/LongBuffer;I)V"; }
    }

    private final IrProgram original;
    private final IrProgram program;
    private final Map<BridgeCallableId, Entry> entries;

    private BridgeCallbackBatching(IrProgram original, IrProgram program, Map<BridgeCallableId, Entry> entries) {
        this.original = original; this.program = program; this.entries = Map.copyOf(entries);
    }

    public IrProgram program() { return program; }
    public Map<BridgeCallableId, Entry> entries() { return entries; }
    public boolean matches(BridgeOwnedCallbackAdmission admission) { return original.equals(admission.program()); }

    @Override public boolean equals(Object other) {
        return other instanceof BridgeCallbackBatching proof && original.equals(proof.original)
                && program.equals(proof.program) && entries.equals(proof.entries);
    }
    @Override public int hashCode() { return java.util.Objects.hash(original, program, entries); }

    public static BridgeCallbackBatching prove(BridgeOwnedCallbackAdmission admission) {
        var original = admission.program();
        var functions = new ArrayList<>(original.functions());
        var byName = new HashMap<String, IrFunction>();
        functions.forEach(function -> byName.put(function.linkageName(), function));
        var entries = new LinkedHashMap<BridgeCallableId, Entry>();
        var roots = new HashSet<>(original.exportRoots());
        for (var entry : admission.callbacks().entries()) {
            var id = entry.callable();
            boolean instance = admission.surface().types().stream().flatMap(type -> type.callables().stream())
                    .anyMatch(method -> method.target().equals(Optional.of(id)) && !method.isStatic()
                            && method.kind() == IrCallableKind.METHOD);
            // One local owner, primitive inputs and a primitive/void result.
            if (!instance || !entry.guardedInputs().equals(List.of(0)) || id.parameters().isEmpty()
                    || !id.parameters().getFirst().equals(IrType.reference(id.owner()))
                    || id.parameters().stream().skip(1).anyMatch(type -> !type.isPrimitive())
                    || (!id.result().isPrimitive() && !id.result().equals(IrType.VOID))) continue;
            var context = admission.callbacks().context().entries().get(id);
            var target = byName.get(context.linkageName());
            if (target == null) continue;
            var candidates = target.blocks().stream().flatMap(block -> block.instructions().stream())
                    .filter(IrCallInstruction.class::isInstance).map(IrCallInstruction.class::cast)
                    .filter(call -> admission.listeners().proxies().stream().anyMatch(proxy ->
                            byName.containsKey(call.targetLinkageName())
                                    && proxy.binaryName().equals(byName.get(call.targetLinkageName()).ownerClass())))
                    .toList();
            if (candidates.size() != 1) continue;
            var callback = candidates.getFirst();
            var proxy = byName.get(callback.targetLinkageName());
            var listener = admission.listeners().proxies().stream()
                    .filter(value -> value.binaryName().equals(proxy.ownerClass())).findFirst().orElseThrow();
            int arity = callback.arguments().size() - 2;
            if (!callback.returnType().equals(IrType.VOID) || arity < 1 || arity > 4
                    || callback.arguments().subList(1, arity + 1).stream().anyMatch(arg -> !arg.type().equals(IrType.I64))) continue;
            int count = new LoopProof(target, callback).countInput();
            if (count < 0) continue;
            var foreign = proxy.blocks().stream().flatMap(block -> block.instructions().stream())
                    .filter(IrForeignCallInstruction.class::isInstance).map(IrForeignCallInstruction.class::cast).toList();
            if (foreign.size() != 1 || foreign.getFirst().invocationContext().isEmpty()) continue;
            var protectedEntry = byName.get(admission.entries().entries().stream()
                    .filter(value -> value.root().callable().equals(id)).findFirst().orElseThrow().function().linkageName());
            if (protectedEntry == null) continue;
            String suffix = "$batch" + entries.size();
            String symbol = foreign.getFirst().targetLinkageName() + "_batch_" + entries.size();
            var replacements = Map.of(target.linkageName(), target.linkageName() + suffix,
                    proxy.linkageName(), proxy.linkageName() + suffix);
            var clones = List.of(clone(proxy, proxy.linkageName() + suffix, instruction -> {
                if (instruction instanceof IrForeignCallInstruction call) return new IrForeignCallInstruction(call.result(),
                        symbol, call.returnType(), call.arguments(), call.invocationContext(), call.sourceSpan());
                return instruction;
            }), clone(target, target.linkageName() + suffix, instruction -> redirect(instruction, replacements)),
                    clone(protectedEntry, protectedEntry.linkageName() + suffix, instruction -> redirect(instruction, replacements)));
            if (clones.stream().anyMatch(value -> byName.containsKey(value.linkageName()))) {
                throw new IllegalArgumentException("callback batch specialization symbol collision");
            }
            functions.addAll(clones);
            roots.add(protectedEntry.linkageName() + suffix);
            entries.put(id, new Entry(id, count, protectedEntry.linkageName() + suffix, symbol,
                    listener.listener().binaryName(), proxy.sourceName(), arity, entries.size()));
        }
        var program = new IrProgram(original.moduleName(), original.classes(), original.staticFields(), original.typeInitializations(),
                original.arrayTypes(), original.stringConstants(), original.dispatchSlots(), functions, original.entryPoint(),
                original.allocationFailure(), roots);
        return new BridgeCallbackBatching(original, program, entries);
    }

    private static IrInstruction redirect(IrInstruction instruction, Map<String, String> replacements) {
        if (instruction instanceof IrCallInstruction call && replacements.containsKey(call.targetLinkageName())) {
            return new IrCallInstruction(call.result(), replacements.get(call.targetLinkageName()), call.returnType(), call.arguments(),
                    call.callKind(), call.devirtualizedFrom(), call.specializationArguments(), call.sourceSpan());
        }
        return instruction;
    }

    private static IrFunction clone(IrFunction function, String name, UnaryOperator<IrInstruction> rewrite) {
        var blocks = function.blocks().stream().map(block -> new IrBasicBlock(block.label(),
                block.instructions().stream().map(rewrite).toList(), block.terminator() instanceof IrInvokeTerminator invoke
                        ? new IrInvokeTerminator(rewrite.apply(invoke.call()), invoke.normalTarget(), invoke.unwindTarget(), invoke.sourceSpan())
                        : block.terminator(), block.sourceSpan())).toList();
        return new IrFunction(function.ownerClass(), function.sourceName(), name, function.returnType(), function.parameters(), blocks,
                function.sourceSpan(), function.sourceFileName(), function.kind());
    }

    /** A single counted loop; no calls, memory access or potentially failing arithmetic between events. */
    private static final class LoopProof {
        private final IrFunction function;
        private final IrCallInstruction callback;
        private final Map<String, IrBasicBlock> blocks = new LinkedHashMap<>();
        private final Map<IrOperand, IrInstruction> definitions = new HashMap<>();
        private final Map<String, Set<String>> predecessors = new HashMap<>();

        LoopProof(IrFunction function, IrCallInstruction callback) {
            this.function = function; this.callback = callback;
            function.blocks().forEach(block -> { blocks.put(block.label(), block); predecessors.put(block.label(), new HashSet<>()); });
            for (var block : function.blocks()) {
                successors(block).forEach(label -> predecessors.get(label).add(block.label()));
                for (var instruction : block.instructions()) {
                    if (instruction instanceof IrPhiInstruction phi) definitions.put(phi.result(), phi);
                    else if (instruction instanceof IrBinaryInstruction binary) definitions.put(binary.result(), binary);
                    else if (instruction instanceof IrNullCheckInstruction check) definitions.put(check.result(), check);
                }
            }
        }

        int countInput() {
            for (var header : function.blocks()) {
                int count = header(header);
                if (count >= 0) return count;
            }
            return -1;
        }

        private int header(IrBasicBlock header) {
            if (!(header.terminator() instanceof IrBranch branch)
                    || !(definitions.get(branch.condition()) instanceof IrBinaryInstruction comparison)
                    || comparison.operator() != IrBinaryOperator.SIGNED_LESS
                    || !(definitions.get(comparison.left()) instanceof IrPhiInstruction induction)
                    || !induction.result().type().equals(IrType.I32) || induction.incoming().size() != 2) return -1;
            var initial = induction.incoming().stream().filter(incoming -> constant(incoming.value(), 0)).toList();
            if (initial.size() != 1) return -1;
            String preheader = initial.getFirst().predecessor();
            var back = induction.incoming().stream().filter(incoming -> !incoming.predecessor().equals(preheader)).findFirst().orElseThrow();
            String latch = back.predecessor();
            if (!(definitions.get(back.value()) instanceof IrBinaryInstruction increment)
                    || increment.operator() != IrBinaryOperator.ADD || !increment.left().equals(induction.result())
                    || !constant(increment.right(), 1)) return -1;
            var bound = invariant(comparison.right(), header.label(), preheader, latch);
            int count = -1;
            for (int index = 1; index < function.parameters().size() - 1; index++) {
                if (function.parameters().get(index).value().equals(bound) && bound.type().equals(IrType.I32)) count = index;
            }
            if (count < 0 || !predecessors.get(header.label()).equals(Set.of(preheader, latch))) return -1;
            var listener = invariant(callback.arguments().getFirst(), header.label(), preheader, latch);
            // Captured before the loop, not reloaded from mutable native state.
            if (listener == null || listener.equals(callback.arguments().getFirst())
                    && header.instructions().stream().anyMatch(instruction -> instruction instanceof IrPhiInstruction phi
                    && phi.result().equals(listener))) return -1;
            var loop = new HashSet<String>(); loop.add(header.label());
            var nullFailures = new ArrayList<String>();
            String current = branch.trueTarget();
            int calls = 0;
            while (!current.equals(header.label())) {
                if (!loop.add(current)) return -1;
                var block = blocks.get(current);
                if (block == null) return -1;
                for (var instruction : block.instructions()) {
                    if (instruction.equals(callback)) { calls++; continue; }
                    if (instruction instanceof IrNullCheckInstruction check
                            && invariant(check.receiver(), header.label(), preheader, latch) != null
                            && invariant(check.receiver(), header.label(), preheader, latch).equals(listener)) continue;
                    if (!pure(instruction)) return -1;
                }
                if (block.terminator() instanceof IrJump jump) current = jump.target();
                else if (calls == 0 && block.terminator() instanceof IrBranch checkBranch
                        && definitions.get(checkBranch.condition()) instanceof IrNullCheckInstruction check
                        && listener.equals(invariant(check.receiver(), header.label(), preheader, latch))) {
                    nullFailures.add(checkBranch.falseTarget()); current = checkBranch.trueTarget();
                } else return -1;
                if (current.equals(header.label()) && !block.label().equals(latch)) return -1;
            }
            if (calls != 1 || !loop.contains(latch)) return -1;
            for (var instruction : header.instructions()) {
                if (instruction instanceof IrPhiInstruction phi) {
                    if (phi.incoming().size() != 2 || !phi.incoming().stream().map(IrPhiIncoming::predecessor)
                            .collect(java.util.stream.Collectors.toSet()).equals(Set.of(preheader, latch))) return -1;
                    if (!phi.result().type().isPrimitive() && !phi.result().equals(callback.arguments().getFirst())) return -1;
                } else if (!pure(instruction)) return -1;
            }
            // No entry into the middle of a chunk, and no exceptional path back into it.
            for (String label : loop) if (!label.equals(header.label())
                    && predecessors.get(label).stream().anyMatch(predecessor -> !loop.contains(predecessor))) return -1;
            var exit = blocks.get(branch.falseTarget());
            if (exit == null || !(exit.terminator() instanceof IrReturnTerminator)
                    || exit.instructions().stream().anyMatch(instruction -> !pure(instruction))) return -1;
            for (String failure : nullFailures) if (reachesAny(failure, loop)) return -1;
            // Removing the proved back edge must leave an acyclic function. This
            // rules out multiple visits, outer loops and irreducible control flow.
            if (cyclic(function.blocks().getFirst().label(), latch, header.label(), new HashSet<>(), new HashSet<>())) return -1;
            return count;
        }

        private IrOperand invariant(IrOperand value, String header, String preheader, String latch) {
            if (definitions.get(value) instanceof IrPhiInstruction phi
                    && blocks.get(header).instructions().contains(phi)) {
                if (phi.incoming().size() != 2) return null;
                var initial = phi.incoming().stream().filter(incoming -> incoming.predecessor().equals(preheader)).findFirst();
                var back = phi.incoming().stream().filter(incoming -> incoming.predecessor().equals(latch)).findFirst();
                return initial.isPresent() && back.isPresent() && back.orElseThrow().value().equals(value)
                        ? initial.orElseThrow().value() : null;
            }
            return value;
        }

        private boolean reachesAny(String start, Set<String> targets) {
            var pending = new ArrayList<String>(); pending.add(start); var visited = new HashSet<String>();
            for (int index = 0; index < pending.size(); index++) {
                String label = pending.get(index);
                if (targets.contains(label)) return true;
                if (visited.add(label)) pending.addAll(successors(blocks.get(label)));
            }
            return false;
        }

        private boolean cyclic(String label, String latch, String header, Set<String> active, Set<String> complete) {
            if (complete.contains(label)) return false;
            if (!active.add(label)) return true;
            for (String next : successors(blocks.get(label))) {
                if (label.equals(latch) && next.equals(header)) continue;
                if (cyclic(next, latch, header, active, complete)) return true;
            }
            active.remove(label); complete.add(label); return false;
        }

        private static boolean constant(IrOperand value, long expected) {
            return value instanceof IrConstant constant && constant.value().longValue() == expected;
        }

        private static boolean pure(IrInstruction instruction) {
            // No division, conversions from floating point, allocations, memory
            // accesses, calls, checks or cleanup may be speculated across callbacks.
            if (instruction instanceof IrBinaryInstruction binary) return (binary.result().type().isIntegral() || binary.result().type().equals(IrType.I1))
                    && binary.left().type().isIntegral() && binary.right().type().isIntegral()
                    && binary.operator() != IrBinaryOperator.DIVIDE && binary.operator() != IrBinaryOperator.REMAINDER;
            if (instruction instanceof IrNumericConversionInstruction convert) return convert.result().type().isIntegral() && convert.value().type().isIntegral();
            return instruction instanceof IrUnaryInstruction unary && unary.result().type().isIntegral() && unary.operand().type().isIntegral();
        }

        private static List<String> successors(IrBasicBlock block) {
            return switch (block.terminator()) {
                case IrJump jump -> List.of(jump.target());
                case IrBranch branch -> List.of(branch.trueTarget(), branch.falseTarget());
                case IrInvokeTerminator invoke -> List.of(invoke.normalTarget(), invoke.unwindTarget());
                case IrThrowTerminator thrown -> thrown.unwindTarget().map(List::of).orElse(List.of());
                case IrSwitchTerminator selected -> Stream.concat(Stream.of(selected.defaultTarget()), selected.cases().stream().map(IrSwitchCase::target)).toList();
                default -> List.of();
            };
        }
    }
}
