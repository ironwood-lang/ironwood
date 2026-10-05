// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Java 21 reference for integration-tests/cases/compiler_scope_stack.iron:
 * the same seeded operations on an ArrayDeque used as FunctionAnalyzer uses
 * its scope stacks (push, pop, peek, head-to-tail iteration, List.copyOf
 * snapshots and the clear-then-addLast restore).
 */
public final class ScopeStackReference {
    private static final String[] NAMES = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"};
    private static long seed = 1234567L;

    private static int next(int bound) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % bound);
    }

    private static String render(Deque<String> stack, int skip) {
        StringBuilder text = new StringBuilder("[");
        int index = 0;
        for (String item : stack) {
            if (index >= skip) {
                if (index > skip) text.append(',');
                text.append(item);
            }
            index++;
        }
        return text.append(']').toString();
    }

    private static <T> void restoreDeque(Deque<T> deque, List<T> values) {
        deque.clear();
        values.forEach(deque::addLast);
    }

    public static void main(String[] args) {
        Deque<String> stack = new ArrayDeque<>();
        List<List<String>> saved = new ArrayList<>();
        int hash = 17;
        for (int step = 0; step < 3000; step++) {
            int op = next(20);
            StringBuilder line = new StringBuilder();
            if (op < 8) {
                String name = NAMES[next(NAMES.length)];
                stack.push(name);
                line.append("push ").append(name);
            } else if (op < 12) {
                try {
                    line.append("pop ").append(stack.pop());
                } catch (NoSuchElementException empty) {
                    line.append("pop empty");
                }
            } else if (op < 13) {
                String head = stack.peek();
                line.append("peek ").append(head == null ? "null" : head);
            } else if (op < 15) {
                line.append(op == 13 ? "all " : "outer ").append(render(stack, op == 13 ? 0 : 1));
            } else if (op < 16) {
                saved.add(List.copyOf(stack));
                line.append("save ").append(saved.size() - 1);
            } else if (op < 18) {
                if (saved.isEmpty()) {
                    line.append("restore none");
                } else {
                    int which = next(saved.size());
                    restoreDeque(stack, saved.get(which));
                    line.append("restore ").append(which);
                }
            } else if (op < 19) {
                String wanted = NAMES[next(NAMES.length)];
                int found = -1;
                int depth = 0;
                for (String item : stack) {
                    if (found < 0 && item.equals(wanted)) found = depth;
                    depth++;
                }
                line.append("find ").append(wanted).append(' ').append(found);
            } else {
                stack.clear();
                line.append("clear");
            }
            line.append(" size=").append(stack.size());
            String text = line.toString();
            hash = 31 * hash + text.hashCode();
            if (step < 200 || step % 100 == 0) System.out.println(text);
        }
        System.out.println("hash " + hash + " saved " + saved.size());
    }
}
