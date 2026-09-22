// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import ironwood.compiler.ir.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** A finite inlining experiment for medium loop bodies behind small direct callers. */
final class SelectiveInlining {
    private SelectiveInlining() {}

    static Set<String> select(IrProgram program) {
        Map<String, IrFunction> functions = new LinkedHashMap<>();
        Map<String, List<IrFunction>> callers = new HashMap<>();
        Map<String, Set<String>> edges = new HashMap<>();
        Set<String> excluded = new HashSet<>();
        program.entryPoint().ifPresent(f -> excluded.add(f.linkageName()));
        program.classes().forEach(c -> {
            c.dispatchEntries().forEach(e -> excluded.add(e.targetLinkageName()));
            c.destructorChain().ifPresent(excluded::add);
            c.constructorRollback().ifPresent(excluded::add);
        });
        for (IrFunction function : program.functions()) {
            functions.put(function.linkageName(), function);
            Set<String> callees = new LinkedHashSet<>();
            operations(function).filter(IrCallInstruction.class::isInstance).map(IrCallInstruction.class::cast)
                    .forEach(call -> {
                        callers.computeIfAbsent(call.targetLinkageName(), ignored -> new ArrayList<>()).add(function);
                        callees.add(call.targetLinkageName());
                    });
            edges.put(function.linkageName(), callees);
        }
        Set<String> result = new LinkedHashSet<>();
        int remaining = 4096;
        for (IrFunction function : functions.values()) {
            String name = function.linkageName();
            int cost = cost(function);
            List<IrFunction> sites = callers.getOrDefault(name, List.of());
            if (function.kind() != IrCallableKind.METHOD || excluded.contains(name) || cost < 64 || cost > 256
                    || sites.isEmpty() || sites.size() > 4 || sites.stream().anyMatch(f -> cost(f) > 48)
                    || !hasLoop(function)) continue;
            Set<String> reached = reachable(name, edges);
            if (reached.contains(name) || result.stream().anyMatch(selected -> reached.contains(selected)
                    || reachable(selected, edges).contains(name))) continue;
            int growth = cost * sites.size();
            if (result.size() >= 8 || growth > remaining) continue;
            result.add(name);
            remaining -= growth;
        }
        return Set.copyOf(result);
    }

    private static Set<String> reachable(String name, Map<String, Set<String>> edges) {
        Set<String> result = new HashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>(edges.getOrDefault(name, Set.of()));
        while (!work.isEmpty()) {
            String current = work.removeFirst();
            if (result.add(current)) work.addAll(edges.getOrDefault(current, Set.of()));
        }
        return result;
    }

    private static boolean hasLoop(IrFunction function) {
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

    private static int cost(IrFunction function) {
        return function.blocks().stream().mapToInt(b -> b.instructions().size() + 1).sum();
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(b -> Stream.concat(b.instructions().stream(),
                b.terminator() instanceof IrInvokeTerminator i ? Stream.of(i.call()) : Stream.empty()));
    }
}
