// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;
import ironwood.compiler.ast.*;
import ironwood.compiler.source.SourceSpan;
import java.util.*;

/**
 * A bounded, typed proof for fresh aggregate results. Elements remain separately
 * owned: a load receives allocation identity only after an immediate null-store
 * detachment is proved. Shared, copied, or arbitrarily mutated arrays do not
 * acquire this proof. No runtime ownership state is emitted.
 */
final class FreshArrayElementAnalysis {
    private final EscapeSummaryAnalyzer summaries;
    private final Map<String, IrFunction> functions;
    private final Set<String> freshResults = new LinkedHashSet<>();
    private final Map<String, Set<SourceSpan>> detachedLoads = new LinkedHashMap<>();

    FreshArrayElementAnalysis(Collection<IrFunction> input, EscapeSummaryAnalyzer summaries) {
        this.summaries = summaries;
        functions = new LinkedHashMap<>();
        input.forEach(function -> functions.put(function.linkageName(), function));
        boolean changed;
        do {
            changed = false;
            for (IrFunction function : input) {
                Check checked = new Check(function);
                checked.run();
                if (checked.returnsFreshElements) { changed |= freshResults.add(function.linkageName()); }
            }
        } while (changed);
        for (IrFunction function : input) {
            Check checked = new Check(function);
            checked.run();
            detachedLoads.put(function.linkageName(), Set.copyOf(checked.loads));
        }
    }

    boolean isDetachedLoad(String function, SourceSpan span) {
        return detachedLoads.getOrDefault(function, Set.of()).contains(span);
    }

    private final class Check {
        private final IrFunction function;
        private final Map<String, IrBasicBlock> blocks = new LinkedHashMap<>();
        private final List<IrInstruction> instructions = new ArrayList<>();
        private final Map<IrInstruction, String> locations = new IdentityHashMap<>();
        private final Map<IrOperand, IrInstruction> definitions = new HashMap<>();
        private final Map<IrOperand, Set<IrOperand>> origins = new HashMap<>();
        private final Map<IrOperand, Boolean> candidates = new LinkedHashMap<>();
        private final Set<SourceSpan> loads = new LinkedHashSet<>();
        private final Map<SourceSpan, SourceSpan> directCreations = new HashMap<>();
        private boolean returnsFreshElements;

        Check(IrFunction function) {
            this.function = function;
            CallableSymbol callable = summaries.callable(function.linkageName());
            if (callable != null) { callable.body().ifPresent(this::collectCreations); }
            for (IrBasicBlock block : function.blocks()) {
                blocks.put(block.label(), block);
                for (IrInstruction instruction : block.instructions()) { add(instruction, block.label()); }
                if (block.terminator() instanceof IrInvokeTerminator invoke) { add(invoke.call(), block.label()); }
            }
            boolean changed;
            do {
                changed = false;
                for (IrInstruction instruction : instructions) {
                    if (instruction instanceof IrReferenceConversionInstruction conversion) {
                        changed |= merge(conversion.result(), origin(conversion.value()));
                    } else if (instruction instanceof IrPhiInstruction phi) {
                        for (IrPhiIncoming incoming : phi.incoming()) {
                            changed |= merge(phi.result(), origin(incoming.value()));
                        }
                    }
                }
            } while (changed);
        }

        private void add(IrInstruction instruction, String block) {
            instructions.add(instruction);
            locations.put(instruction, block);
            IrOperand result = result(instruction);
            if (result != null) {
                definitions.put(result, instruction);
                if (!(instruction instanceof IrReferenceConversionInstruction)
                        && !(instruction instanceof IrPhiInstruction)) {
                    origins.put(result, Set.of(result));
                } else {
                    origins.put(result, new LinkedHashSet<>());
                }
            }
            if (instruction instanceof IrArrayAllocateInstruction array
                    && array.result().type().elementType().isReference()) {
                candidates.put(array.result(), true);
            } else if (result != null && result.type().isArray()
                    && result.type().elementType().isReference()) {
                Set<String> targets = callTargets(instruction);
                if (!targets.isEmpty() && freshResults.containsAll(targets)) { candidates.put(result, false); }
            }
        }

        void run() {
            Set<IrOperand> proved = new LinkedHashSet<>();
            for (var candidate : candidates.entrySet()) {
                Set<SourceSpan> candidateLoads = check(candidate.getKey(), candidate.getValue());
                if (candidateLoads != null) {
                    proved.add(candidate.getKey());
                    loads.addAll(candidateLoads);
                }
            }
            // A duplicated finally region can lower the same source load with
            // different origins. A source-span proof must hold in every copy.
            for (IrInstruction instruction : instructions) {
                if (instruction instanceof IrArrayLoadInstruction load
                        && (origin(load.array()).size() != 1 || !proved.containsAll(origin(load.array())))) {
                    loads.remove(load.sourceSpan());
                }
            }
            List<IrOperand> returned = blocks.values().stream()
                    .map(IrBasicBlock::terminator).filter(IrReturnTerminator.class::isInstance)
                    .map(IrReturnTerminator.class::cast).flatMap(value -> value.value().stream()).toList();
            returnsFreshElements = !returned.isEmpty() && returned.stream().allMatch(value ->
                    value instanceof IrNull || origin(value).size() == 1
                            && proved.containsAll(origin(value)));
        }

        private Set<SourceSpan> check(IrOperand array, boolean local) {
            Set<SourceSpan> result = new LinkedHashSet<>();
            Map<IrOperand, IrArrayStoreInstruction> entries = new LinkedHashMap<>();
            for (var alias : origins.entrySet()) {
                if (alias.getValue().contains(array) && alias.getValue().size() != 1) { return null; }
            }
            for (IrInstruction instruction : instructions) {
                if (instruction instanceof IrArrayStoreInstruction store) {
                    if (is(store.value(), array)) { return null; }
                    if (is(store.array(), array) && !(store.value() instanceof IrNull)) {
                        if (!local || origin(store.value()).size() != 1) { return null; }
                        IrOperand entry = origin(store.value()).iterator().next();
                        IrInstruction creation = definitions.get(entry);
                        if (!fresh(creation) || !Objects.equals(directCreations.get(store.sourceSpan()),
                                creation.sourceSpan()) || entries.putIfAbsent(entry, store) != null
                                || repeatsWithout(store, creation)) { return null; }
                    }
                } else if (instruction instanceof IrArrayLoadInstruction load && is(load.array(), array)) {
                    if (!immediatelyDetached(load, array)) { return null; }
                    result.add(load.sourceSpan());
                } else if (instruction instanceof IrFieldStoreInstruction store && is(store.value(), array)
                        || instruction instanceof IrStaticFieldStoreInstruction staticStore && is(staticStore.value(), array)) {
                    return null;
                } else if (instruction instanceof IrSystemArrayCopyInstruction copy
                        && (is(copy.source(), array) || is(copy.destination(), array))) { return null; }
                if (arguments(instruction).stream().anyMatch(value -> is(value, array))) { return null; }
            }
            for (var entry : entries.entrySet()) {
                for (IrInstruction instruction : instructions) {
                    if (instruction instanceof IrFieldStoreInstruction store && is(store.value(), entry.getKey())
                            || instruction instanceof IrStaticFieldStoreInstruction staticStore && is(staticStore.value(), entry.getKey())
                            || instruction instanceof IrFreeInstruction free && is(free.allocation(), entry.getKey())
                            || instruction instanceof IrArrayStoreInstruction arrayStore
                            && is(arrayStore.value(), entry.getKey()) && instruction != entry.getValue()) { return null; }
                    List<IrOperand> arguments = arguments(instruction);
                    for (int index = 0; index < arguments.size(); index++) {
                        if (!is(arguments.get(index), entry.getKey())) { continue; }
                        CallableSymbol target = instruction instanceof IrCallInstruction call
                                ? summaries.callable(call.targetLinkageName()) : null;
                        if (target == null || !target.isConstructor() || index != 0
                                || summaries.summary(target).thisEscapesWithoutReturn()) { return null; }
                    }
                }
                for (IrBasicBlock block : blocks.values()) {
                    if (block.terminator() instanceof IrReturnTerminator returned
                            && returned.value().filter(value -> is(value, entry.getKey())).isPresent()
                            || block.terminator() instanceof IrThrowTerminator thrown
                            && is(thrown.exception(), entry.getKey())) { return null; }
                }
            }
            return result;
        }

        private boolean immediatelyDetached(IrArrayLoadInstruction load, IrOperand array) {
            IrBasicBlock block = blocks.get(locations.get(load));
            int position = block.instructions().indexOf(load) + 1;
            Set<String> visited = new HashSet<>();
            while (visited.add(block.label())) {
                IrOperand checked = null;
                for (int index = position; index < block.instructions().size(); index++) {
                    IrInstruction next = block.instructions().get(index);
                    if (next instanceof IrArrayStoreInstruction store) {
                        return is(store.array(), array) && same(store.index(), load.index())
                                && store.value() instanceof IrNull;
                    }
                    if (next instanceof IrNullCheckInstruction check && is(check.receiver(), array)) {
                        checked = check.result();
                    } else if (next instanceof IrArrayBoundsCheckInstruction check && is(check.array(), array)
                            && same(check.index(), load.index())) {
                        checked = check.result();
                    } else { return false; }
                }
                String target;
                if (block.terminator() instanceof IrJump jump) { target = jump.target(); }
                else if (block.terminator() instanceof IrBranch branch && Objects.equals(branch.condition(), checked)) {
                    target = branch.trueTarget();
                } else { return false; }
                block = blocks.get(target);
                if (block == null) { return false; }
                position = 0;
            }
            return false;
        }

        private void collectCreations(Statement statement) {
            if (statement instanceof Block block) { block.statements().forEach(this::collectCreations); }
            else if (statement instanceof AssignmentStatement assignment
                    && assignment.target() instanceof ArrayAccessExpression
                    && assignment.value() instanceof NewExpression allocation) {
                directCreations.put(assignment.span(), allocation.span());
            } else if (statement instanceof ExpressionStatement expression
                    && expression.expression() instanceof AssignmentExpression assignment
                    && assignment.operator() == AssignmentOperator.ASSIGN
                    && assignment.target() instanceof ArrayAccessExpression
                    && assignment.value() instanceof NewExpression allocation) {
                directCreations.put(assignment.span(), allocation.span());
            } else if (statement instanceof ForStatement loop) { collectCreations(loop.body()); }
            else if (statement instanceof WhileStatement loop) { collectCreations(loop.body()); }
            else if (statement instanceof DoWhileStatement loop) { collectCreations(loop.body()); }
            else if (statement instanceof IfStatement selection) {
                collectCreations(selection.thenBranch());
                selection.elseBranch().ifPresent(this::collectCreations);
            } else if (statement instanceof TryStatement guarded) {
                collectCreations(guarded.body());
                guarded.catches().forEach(caught -> collectCreations(caught.body()));
                guarded.finallyBlock().ifPresent(this::collectCreations);
            }
        }

        private boolean fresh(IrInstruction instruction) {
            if (instruction instanceof IrAllocateInstruction || instruction instanceof IrArrayAllocateInstruction) {
                return true;
            }
            if (instruction == null) { return false; }
            Set<String> targets = callTargets(instruction);
            return !targets.isEmpty() && targets.stream().allMatch(target ->
                    summaries.summary(target) != null && summaries.summary(target).returnsOwnedFresh());
        }

        private boolean repeatsWithout(IrInstruction store, IrInstruction creation) {
            String from = locations.get(store), required = locations.get(creation);
            if (from.equals(required) && instructions.indexOf(creation) < instructions.indexOf(store)) { return false; }
            Set<String> visited = new HashSet<>();
            Deque<String> pending = new ArrayDeque<>(successors(blocks.get(from).terminator()));
            while (!pending.isEmpty()) {
                String block = pending.removeFirst();
                if (block.equals(required) || !visited.add(block)) { continue; }
                if (block.equals(from)) { return true; }
                pending.addAll(successors(blocks.get(block).terminator()));
            }
            return false;
        }

        private Set<String> callTargets(IrInstruction instruction) {
            if (instruction instanceof IrCallInstruction call) { return Set.of(call.targetLinkageName()); }
            String name = instruction instanceof IrVirtualCallInstruction call ? call.slot().methodName()
                    : instruction instanceof IrInterfaceCallInstruction call ? call.slot().methodName() : null;
            return name == null ? Set.of() : new LinkedHashSet<>(summaries
                    .boundTargets(function.linkageName(), instruction.sourceSpan(), name).stream()
                    .map(CallableSymbol::linkageName).toList());
        }

        private boolean merge(IrOperand result, Set<IrOperand> values) {
            return origins.computeIfAbsent(result, ignored -> new LinkedHashSet<>()).addAll(values);
        }
        private Set<IrOperand> origin(IrOperand value) {
            if (value instanceof IrNull) { return Set.of(); }
            return origins.getOrDefault(value, Set.of(value));
        }
        private boolean is(IrOperand value, IrOperand root) { return origin(value).contains(root); }
        private boolean same(IrOperand first, IrOperand second) {
            return first.equals(second) || first instanceof IrConstant a && second instanceof IrConstant b
                    && a.type().equals(b.type()) && a.value().equals(b.value());
        }
    }

    private static IrOperand result(IrInstruction instruction) {
        if (instruction instanceof IrAllocateInstruction value) { return value.result(); }
        if (instruction instanceof IrArrayAllocateInstruction value) { return value.result(); }
        if (instruction instanceof IrArrayLoadInstruction value) { return value.result(); }
        if (instruction instanceof IrFieldLoadInstruction value) { return value.result(); }
        if (instruction instanceof IrStaticFieldLoadInstruction value) { return value.result(); }
        if (instruction instanceof IrReferenceConversionInstruction value) { return value.result(); }
        if (instruction instanceof IrPhiInstruction value) { return value.result(); }
        if (instruction instanceof IrCallInstruction value) { return value.result().orElse(null); }
        if (instruction instanceof IrVirtualCallInstruction value) { return value.result().orElse(null); }
        if (instruction instanceof IrInterfaceCallInstruction value) { return value.result().orElse(null); }
        return null;
    }
    private static List<IrOperand> arguments(IrInstruction instruction) {
        if (instruction instanceof IrCallInstruction value) { return value.arguments(); }
        if (instruction instanceof IrVirtualCallInstruction value) { return value.arguments(); }
        if (instruction instanceof IrInterfaceCallInstruction value) { return value.arguments(); }
        return List.of();
    }
    private static Set<String> successors(IrTerminator terminator) {
        if (terminator instanceof IrJump jump) { return Set.of(jump.target()); }
        if (terminator instanceof IrBranch branch) { return new HashSet<>(List.of(branch.trueTarget(), branch.falseTarget())); }
        if (terminator instanceof IrInvokeTerminator invoke) { return Set.of(invoke.normalTarget(), invoke.unwindTarget()); }
        if (terminator instanceof IrThrowTerminator thrown) { return thrown.unwindTarget().map(Set::of).orElse(Set.of()); }
        if (terminator instanceof IrSwitchTerminator selection) {
            Set<String> result = new HashSet<>();
            result.add(selection.defaultTarget());
            selection.cases().forEach(branch -> result.add(branch.target()));
            return result;
        }
        return Set.of();
    }
}
