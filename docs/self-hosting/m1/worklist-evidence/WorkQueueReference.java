// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Java 21 reference transcript for compiler_work_queue.iron. The reachability
 * loop is ClosedWorldEffectAnalyzer.reachableBlocks' ArrayDeque/LinkedHashSet
 * algorithm over the fixture's CFG. Logical behavior only.
 */
public final class WorkQueueReference {

    private static final Map<String, List<String>> SUCCESSORS = Map.of(
            "entry", List.of("branch"), "branch", List.of("then", "else"), "then", List.of("switch"),
            "else", List.of("loop"), "loop", List.of("loop", "invoke"),
            "switch", List.of("default", "case1", "case2"), "invoke", List.of("normal", "unwind"),
            "case1", List.of("default"), "normal", List.of("branch"), "dead", List.of("entry"));

    public static void main(String[] args) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        StringBuilder taken = new StringBuilder();
        int next = 0;
        for (int round = 0; round < 6; round++) {
            for (int added = 0; added < 3; added++) queue.add("n" + next++);
            taken.append(queue.removeFirst()).append(' ');
        }
        while (next < 24) queue.add("n" + next++);
        System.out.println("size:" + queue.size());
        while (!queue.isEmpty()) taken.append(queue.removeFirst()).append(' ');
        System.out.println("order:" + taken.toString().trim());

        Set<String> reachable = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add("entry");
        StringBuilder visited = new StringBuilder();
        while (!pending.isEmpty()) {
            String label = pending.removeFirst();
            if (!reachable.add(label)) continue;
            visited.append(label).append(' ');
            pending.addAll(SUCCESSORS.getOrDefault(label, List.of()));
        }
        System.out.println("reachable:" + visited.toString().trim());

        try {
            new ArrayDeque<String>().removeFirst();
            throw new AssertionError("empty take");
        } catch (NoSuchElementException expected) {
            // Same failure as WorkQueue.removeFirst.
        }
        try {
            new ArrayDeque<String>().add(null);
            throw new AssertionError("null add");
        } catch (NullPointerException expected) {
            // Same failure as WorkQueue.add.
        }
    }
}
