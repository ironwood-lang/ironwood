// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Bounded post-validation CFG versioning under read-only, permanent state-2 facts. */
final class InitializedTypeSpecializer {
    private static final int MAX_TYPES = 4;
    private static final int MAX_BODY = 1200;
    private static final int MAX_GROUP_FUNCTIONS = 32;
    private static final int MAX_GROUP_COST = 4096;
    private static final int MAX_TOTAL_COST = 8192;
    private static final String SUFFIX = ".$initialized.";
    private final IrProgram program;
    private final Map<String, IrFunction> functions = new LinkedHashMap<>();
    private final Map<String, Set<String>> demands = new LinkedHashMap<>();
    private final Set<IrStaticField> immutableEnums = new LinkedHashSet<>();

    private InitializedTypeSpecializer(IrProgram program) {
        this.program = program;
        program.functions().forEach(f -> functions.put(f.linkageName(), f));
    }

    static IrProgram specialize(IrProgram program) {
        if (program.functions().stream().anyMatch(f -> f.linkageName().contains(SUFFIX)
                || operations(f).anyMatch(IrTypeInitializedInstruction.class::isInstance))) return program;
        return new InitializedTypeSpecializer(program).run();
    }

    private IrProgram run() {
        // Source safety and final-field validation have already completed. Verify
        // the compiler-owned enum publication shape as well before using its address.
        for (IrStaticField field : program.staticFields()) {
            if (!field.isFinal() || !(field.initialValue() instanceof IrEnumConstant constant)) continue;
            boolean valid = true;
            int stores = 0;
            for (IrFunction function : program.functions()) {
                for (IrInstruction instruction : operations(function).toList()) {
                    if (instruction instanceof IrStaticFieldStoreInstruction store && store.field().equals(field)) {
                        stores++;
                        valid &= function.kind() == IrCallableKind.CLASS_INITIALIZER
                                && function.ownerClass().equals(field.ownerClass())
                                && store.value().equals(constant);
                    }
                }
            }
            if (valid && stores == 1) immutableEnums.add(field);
        }
        functions.forEach((name, function) -> {
            Set<String> types = new LinkedHashSet<>();
            operations(function).filter(IrEnsureTypeInitializedInstruction.class::isInstance)
                    .map(IrEnsureTypeInitializedInstruction.class::cast).forEach(e -> types.add(e.typeName()));
            demands.put(name, types);
        });
        // A finite monotone union handles recursive direct call graphs without
        // recursive traversal or unbounded clone contexts.
        boolean changed;
        do {
            changed = false;
            for (IrFunction function : functions.values()) {
                for (String callee : callees(function)) {
                    changed |= demands.get(function.linkageName()).addAll(demands.getOrDefault(callee, Set.of()));
                }
            }
        } while (changed);
        Set<String> reachable = new LinkedHashSet<>();
        ClosedWorldPruner.prune(program).functions().forEach(f -> reachable.add(f.linkageName()));
        List<IrFunction> roots = functions.values().stream()
                .filter(f -> reachable.contains(f.linkageName()) && f.kind() == IrCallableKind.METHOD)
                .filter(f -> cost(f) <= MAX_BODY && hasLoop(f))
                .filter(f -> !demands.get(f.linkageName()).isEmpty()
                        && demands.get(f.linkageName()).size() <= MAX_TYPES)
                .sorted(Comparator.<IrFunction>comparingLong(this::localBenefit).reversed()
                        .thenComparing(IrFunction::linkageName)).toList();
        Map<String, IrFunction> replacements = new LinkedHashMap<>();
        List<IrFunction> clones = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        int budget = Math.min(MAX_TOTAL_COST, program.functions().stream().mapToInt(InitializedTypeSpecializer::cost).sum() / 2);
        int groupId = 0;
        for (IrFunction root : roots) {
            if (covered.contains(root.linkageName())) continue;
            Set<String> facts = demands.get(root.linkageName());
            List<IrFunction> group = group(root, facts);
            int cost = group.stream().mapToInt(InitializedTypeSpecializer::cost).sum();
            long benefit = group.stream().mapToLong(this::localBenefit).sum();
            if (group.size() > MAX_GROUP_FUNCTIONS || cost > MAX_GROUP_COST || cost > budget
                    || benefit < facts.size() * 2L) continue;
            Map<String, String> targets = new LinkedHashMap<>();
            String suffix = SUFFIX + groupId++;
            // Even a recursive root gets a guardless clone, so recursive edges
            // do not repeat its entry tests. Account for this extra body explicitly.
            boolean recursiveRoot = group.stream().anyMatch(f -> callees(f).contains(root.linkageName()));
            int extra = recursiveRoot ? cost(root) : 0;
            if (cost + extra > budget || cost + extra > MAX_GROUP_COST) continue;
            for (IrFunction function : group) {
                if (function != root || recursiveRoot) targets.put(function.linkageName(), function.linkageName() + suffix);
            }
            for (IrFunction function : group) {
                List<IrBasicBlock> fast = fastBlocks(function, facts, targets);
                if (function == root) replacements.put(root.linkageName(), guarded(root, facts, fast));
                if (targets.containsKey(function.linkageName())) {
                    clones.add(copy(function, targets.get(function.linkageName()), fast));
                }
                covered.add(function.linkageName());
            }
            budget -= cost + extra;
        }
        if (replacements.isEmpty()) return program;
        List<IrFunction> result = new ArrayList<>();
        program.functions().forEach(f -> result.add(replacements.getOrDefault(f.linkageName(), f)));
        result.addAll(clones);
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(),
                program.typeInitializations(), program.arrayTypes(), program.stringConstants(),
                program.dispatchSlots(), result, program.entryPoint().map(f -> replacements.getOrDefault(f.linkageName(), f)),
                program.allocationFailure());
    }

    private List<IrFunction> group(IrFunction root, Set<String> facts) {
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>();
        List<IrFunction> result = new ArrayList<>();
        work.add(root.linkageName());
        while (!work.isEmpty()) {
            String name = work.removeFirst();
            if (!visited.add(name)) continue;
            IrFunction function = functions.get(name);
            if (function == null || cost(function) > MAX_BODY
                    || demands.get(name).stream().noneMatch(facts::contains)) continue;
            result.add(function);
            if (result.size() > MAX_GROUP_FUNCTIONS) break;
            work.addAll(callees(function));
        }
        return result;
    }

    private long localBenefit(IrFunction function) {
        return operations(function).filter(i -> i instanceof IrEnsureTypeInitializedInstruction
                || i instanceof IrStaticFieldLoadInstruction load && immutableEnums.contains(load.field())).count();
    }

    private List<IrBasicBlock> fastBlocks(IrFunction function, Set<String> facts, Map<String, String> targets) {
        List<IrBasicBlock> blocks = new ArrayList<>();
        for (IrBasicBlock block : function.blocks()) {
            List<IrInstruction> instructions = block.instructions().stream()
                    .filter(i -> !(i instanceof IrEnsureTypeInitializedInstruction e && facts.contains(e.typeName())))
                    .map(i -> fastInstruction(i, facts, targets)).toList();
            IrTerminator terminator = block.terminator();
            if (terminator instanceof IrInvokeTerminator invoke) {
                terminator = invoke.call() instanceof IrEnsureTypeInitializedInstruction e && facts.contains(e.typeName())
                        ? new IrJump(invoke.normalTarget(), invoke.sourceSpan())
                        : new IrInvokeTerminator(fastInstruction(invoke.call(), facts, targets),
                                invoke.normalTarget(), invoke.unwindTarget(), invoke.sourceSpan());
            }
            blocks.add(new IrBasicBlock(block.label(), instructions, terminator, block.sourceSpan()));
        }
        return removeUnreachable(blocks);
    }

    private IrInstruction fastInstruction(IrInstruction instruction, Set<String> facts, Map<String, String> targets) {
        if (instruction instanceof IrCallInstruction call && targets.containsKey(call.targetLinkageName())) {
            return new IrCallInstruction(call.result(), targets.get(call.targetLinkageName()), call.returnType(),
                    call.arguments(), call.callKind(), call.devirtualizedFrom(), call.specializationArguments(), call.sourceSpan());
        }
        if (instruction instanceof IrStaticFieldLoadInstruction load && facts.contains(load.field().ownerClass())
                && immutableEnums.contains(load.field())) {
            return new IrReferenceConversionInstruction(load.result(), load.field().initialValue(), load.sourceSpan());
        }
        return instruction;
    }

    private static IrFunction guarded(IrFunction function, Set<String> facts, List<IrBasicBlock> fast) {
        Set<Integer> parameterIds = new LinkedHashSet<>();
        function.parameters().forEach(p -> parameterIds.add(p.value().id()));
        int[] next = {0};
        IrCfgRenamer scanner = new IrCfgRenamer(v -> {
            next[0] = Math.max(next[0], v.id() + 1);
            return v;
        }, label -> label);
        function.parameters().forEach(p -> next[0] = Math.max(next[0], p.value().id() + 1));
        function.blocks().forEach(scanner::block);
        Map<Integer, Integer> values = new HashMap<>();
        String prefix = "$initialized.";
        while (hasPrefix(function, prefix)) prefix += "x.";
        String labels = prefix;
        IrCfgRenamer renamer = new IrCfgRenamer(v -> parameterIds.contains(v.id()) ? v
                : new IrValueReference(values.computeIfAbsent(v.id(), ignored -> next[0]++), v.type(), v.sourceSpan()),
                label -> labels + "body." + label);
        List<IrBasicBlock> renamed = fast.stream().map(renamer::block).toList();
        List<IrBasicBlock> result = new ArrayList<>();
        List<String> ordered = facts.stream().sorted().toList();
        for (int index = 0; index < ordered.size(); index++) {
            IrValueReference condition = new IrValueReference(next[0]++, IrType.I1, function.sourceSpan());
            String success = index + 1 == ordered.size() ? renamed.getFirst().label() : labels + "guard." + (index + 1);
            result.add(new IrBasicBlock(labels + "guard." + index,
                    List.of(new IrTypeInitializedInstruction(condition, ordered.get(index), function.sourceSpan())),
                    new IrBranch(condition, success, function.blocks().getFirst().label(), function.sourceSpan()), function.sourceSpan()));
        }
        result.addAll(renamed);
        result.addAll(function.blocks());
        return copy(function, function.linkageName(), result);
    }

    private static boolean hasPrefix(IrFunction function, String prefix) {
        return function.blocks().stream().anyMatch(b -> b.label().startsWith(prefix));
    }

    private static IrFunction copy(IrFunction function, String name, List<IrBasicBlock> blocks) {
        return new IrFunction(function.ownerClass(), function.sourceName(), name, function.returnType(),
                function.parameters(), blocks, function.sourceSpan(), function.sourceFileName(), function.kind());
    }

    private static List<IrBasicBlock> removeUnreachable(List<IrBasicBlock> blocks) {
        Map<String, IrBasicBlock> byLabel = new LinkedHashMap<>();
        blocks.forEach(b -> byLabel.put(b.label(), b));
        Set<String> reached = new LinkedHashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>();
        work.add(blocks.getFirst().label());
        while (!work.isEmpty()) {
            String label = work.removeFirst();
            if (reached.add(label)) work.addAll(successors(byLabel.get(label)));
        }
        Map<String, Set<String>> predecessors = new LinkedHashMap<>();
        reached.forEach(label -> predecessors.put(label, new LinkedHashSet<>()));
        reached.forEach(label -> successors(byLabel.get(label)).forEach(s -> predecessors.get(s).add(label)));
        return blocks.stream().filter(b -> reached.contains(b.label())).map(b -> new IrBasicBlock(b.label(),
                b.instructions().stream().map(i -> i instanceof IrPhiInstruction phi
                        ? (IrInstruction) new IrPhiInstruction(phi.result(), phi.incoming().stream()
                                .filter(p -> predecessors.get(b.label()).contains(p.predecessor())).toList(), phi.sourceSpan())
                        : i).toList(), b.terminator(), b.sourceSpan())).toList();
    }

    private static List<String> successors(IrBasicBlock block) {
        return switch (block.terminator()) {
            case IrJump t -> List.of(t.target());
            case IrBranch t -> List.of(t.trueTarget(), t.falseTarget());
            case IrInvokeTerminator t -> List.of(t.normalTarget(), t.unwindTarget());
            case IrThrowTerminator t -> t.unwindTarget().map(u -> List.of(t.normalTarget(), u)).orElse(List.of());
            case IrSwitchTerminator t -> Stream.concat(Stream.of(t.defaultTarget()), t.cases().stream().map(IrSwitchCase::target)).toList();
            default -> List.of();
        };
    }

    private static boolean hasLoop(IrFunction function) {
        // Kahn's topological elimination detects all CFG cycles, including
        // irreducible ones, without assumptions about frontend label spelling.
        Map<String, Integer> degree = new LinkedHashMap<>();
        Map<String, IrBasicBlock> blocks = new LinkedHashMap<>();
        function.blocks().forEach(b -> { degree.put(b.label(), 0); blocks.put(b.label(), b); });
        function.blocks().forEach(b -> successors(b).forEach(s -> degree.compute(s, (k, n) -> n + 1)));
        ArrayDeque<String> work = new ArrayDeque<>();
        degree.forEach((label, n) -> { if (n == 0) work.add(label); });
        int removed = 0;
        while (!work.isEmpty()) {
            String label = work.removeFirst();
            removed++;
            for (String target : successors(blocks.get(label))) {
                if (degree.compute(target, (k, n) -> n - 1) == 0) work.add(target);
            }
        }
        return removed != blocks.size();
    }

    private static int cost(IrFunction function) {
        return function.blocks().stream().mapToInt(b -> b.instructions().size() + 1).sum();
    }

    private static List<String> callees(IrFunction function) {
        return operations(function).filter(IrCallInstruction.class::isInstance)
                .map(IrCallInstruction.class::cast).map(IrCallInstruction::targetLinkageName).distinct().toList();
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(b -> Stream.concat(b.instructions().stream(),
                b.terminator() instanceof IrInvokeTerminator i ? Stream.of(i.call()) : Stream.empty()));
    }
}
