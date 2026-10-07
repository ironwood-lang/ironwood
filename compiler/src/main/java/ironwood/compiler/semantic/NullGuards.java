// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.BinaryOperator;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UnaryOperator;
import ironwood.compiler.ir.IrBranch;
import ironwood.compiler.ir.IrField;
import ironwood.compiler.ir.IrInvokeTerminator;
import ironwood.compiler.ir.IrJump;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrReturnTerminator;
import ironwood.compiler.ir.IrSwitchCase;
import ironwood.compiler.ir.IrSwitchTerminator;
import ironwood.compiler.ir.IrTerminator;
import ironwood.compiler.ir.IrThrowTerminator;
import ironwood.compiler.ir.IrUnreachable;
import ironwood.compiler.ir.IrValueReference;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Null tests that make later null checks of the same reference redundant (D287).
 *
 * <p>A reference compared with null, tested with instanceof or passed through a null
 * check is non-null on the branch edge that the outcome selects. The edge's target has
 * the test as its only predecessor, so the reference is non-null wherever that target
 * dominates, and a null check there is not emitted. Facts name SSA values, which never
 * change, and the reference conversions of them.
 *
 * <p>A final instance field read from one receiver value is named by the receiver and
 * the field. Only constructors store such a field, whose loads are not named, and a
 * destructor that frees an owned field of its class, which stores null. A guard of a
 * field the function may store therefore ends where lowering frees the field, and does
 * not cover a loop entered after it, whose back edge may bring a later free around.
 *
 * <p>Dominance is judged on the control-flow graph lowered so far. Lowering places code
 * in a block only after every forward edge into it exists; edges added later are loop
 * back edges, which do not change dominance. {@link #verify} checks every omitted null
 * check again on the finished graph and its field stores.
 */
final class NullGuards {
    /** References known non-null when a condition is true and when it is false. */
    record Facts(List<IrOperand> whenTrue, List<IrOperand> whenFalse) {
        static final Facts NONE = new Facts(List.of(), List.of());

        Facts negate() {
            return new Facts(whenFalse, whenTrue);
        }
    }

    /** A field store at an instruction index of a block. */
    record Store(String block, int index, String owner, String name) {
    }

    /** Successor labels by block label, with the label of the entry block. */
    record Graph(String entry, Map<String, List<String>> successors) {
        boolean onlyEdge(String from, String to) {
            int edges = 0;
            boolean fromSource = false;
            for (Map.Entry<String, List<String>> block : successors.entrySet()) {
                for (String successor : block.getValue()) {
                    if (successor.equals(to)) {
                        edges++;
                        fromSource |= block.getKey().equals(from);
                    }
                }
            }
            return edges == 1 && fromSource;
        }

        /** Whether every path from the entry to {@code block} passes {@code dominator}. */
        boolean dominates(String dominator, String block) {
            if (!reachable(List.of(entry), null).contains(block)) {
                return false;
            }
            return dominator.equals(block) || !reachable(List.of(entry), dominator).contains(block);
        }

        /**
         * Whether a store can run after {@code target} is entered and before
         * {@code position} in {@code block} is reached, without entering {@code target} again.
         */
        boolean storeBetween(String target, String block, int position, Store store) {
            if (store.block().equals(target)) {
                return !block.equals(target) || store.index() < position;
            }
            if (store.block().equals(block)) {
                return store.index() < position || after(block, target).contains(block);
            }
            return after(target, target).contains(store.block())
                    && after(store.block(), target).contains(block);
        }

        /** Blocks reached from the successors of {@code from} without entering {@code avoided}. */
        private Set<String> after(String from, String avoided) {
            return reachable(successors.getOrDefault(from, List.of()), avoided);
        }

        private Set<String> reachable(List<String> starts, String avoided) {
            ArrayDeque<String> work = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            for (String start : starts) {
                if (!start.equals(avoided) && seen.add(start)) {
                    work.addLast(start);
                }
            }
            while (!work.isEmpty()) {
                for (String successor : successors.getOrDefault(work.removeFirst(), List.of())) {
                    if (!successor.equals(avoided) && seen.add(successor)) {
                        work.addLast(successor);
                    }
                }
            }
            return seen;
        }
    }

    private record FinalField(IrOperand receiver, String owner, String name, boolean mayStore) {
    }

    private record Edge(String test, String target, List<Integer> loops) {
    }

    private record Omitted(Object fact, Edge edge, String block, int position) {
    }

    private final IdentityHashMap<Expression, Facts> tests = new IdentityHashMap<>();
    private final Map<Object, List<Edge>> guards = new HashMap<>();
    private final Map<IrOperand, IrOperand> conversions = new HashMap<>();
    private final Map<IrOperand, FinalField> finalFieldLoads = new HashMap<>();
    private final List<Omitted> omitted = new ArrayList<>();

    static List<String> successors(IrTerminator terminator) {
        if (terminator == null) {
            return List.of();
        }
        return switch (terminator) {
            case IrJump jump -> List.of(jump.target());
            case IrBranch branch -> List.of(branch.trueTarget(), branch.falseTarget());
            case IrInvokeTerminator invoke -> List.of(invoke.normalTarget(), invoke.unwindTarget());
            case IrSwitchTerminator selection -> Stream.concat(Stream.of(selection.defaultTarget()),
                    selection.cases().stream().map(IrSwitchCase::target)).toList();
            case IrThrowTerminator thrown -> Stream.concat(Stream.of(thrown.normalTarget()),
                    thrown.unwindTarget().stream()).toList();
            case IrReturnTerminator ignored -> List.of();
            case IrUnreachable ignored -> List.of();
        };
    }

    /** Records the outcome of lowering {@code test}; a null reference records nothing. */
    void tested(Expression test, IrOperand reference, boolean nonNullWhenTrue) {
        if (reference instanceof IrValueReference && reference.type().isReference()) {
            List<IrOperand> tested = List.of(reference);
            tests.put(test, nonNullWhenTrue ? new Facts(tested, List.of())
                    : new Facts(List.of(), tested));
        } else {
            tests.remove(test);
        }
    }

    /** The facts of a lowered condition, through negation and short-circuit operators. */
    Facts facts(Expression condition) {
        if (condition instanceof UnaryExpression unary && unary.operator() == UnaryOperator.NOT) {
            return facts(unary.operand()).negate();
        }
        if (condition instanceof BinaryExpression binary
                && (binary.operator() == BinaryOperator.LOGICAL_AND
                || binary.operator() == BinaryOperator.LOGICAL_OR)) {
            Facts left = facts(binary.left());
            Facts right = facts(binary.right());
            return binary.operator() == BinaryOperator.LOGICAL_AND
                    ? new Facts(union(left.whenTrue(), right.whenTrue()), List.of())
                    : new Facts(List.of(), union(left.whenFalse(), right.whenFalse()));
        }
        return tests.getOrDefault(condition, Facts.NONE);
    }

    void converted(IrOperand result, IrOperand source) {
        conversions.put(result, source);
    }

    void loadedFinalField(IrOperand result, IrOperand receiver, IrField field, boolean mayStore) {
        finalFieldLoads.put(result, new FinalField(receiver, field.ownerClass(), field.name(),
                mayStore));
    }

    /** Ends the guards of a field that the function has just stored, on every receiver. */
    void stored(IrField field) {
        guards.keySet().removeIf(fact -> fact instanceof FinalField loaded
                && loaded.owner().equals(field.ownerClass()) && loaded.name().equals(field.name()));
    }

    /**
     * Records that {@code reference} is non-null on the edge from {@code test} to
     * {@code target}, inside the given open loops.
     */
    void guard(IrOperand reference, String test, String target, List<Integer> loops) {
        Edge edge = new Edge(test, target, List.copyOf(loops));
        for (Object fact : facts(reference)) {
            guards.computeIfAbsent(fact, ignored -> new ArrayList<>()).add(edge);
        }
    }

    /**
     * Whether a guard proves {@code reference} non-null at {@code position} in
     * {@code block}, inside the given open loops; records the omission.
     */
    boolean guarded(IrOperand reference, String block, int position, List<Integer> loops,
                    Supplier<Graph> graph) {
        Graph current = null;
        for (Object fact : facts(reference)) {
            for (Edge edge : guards.getOrDefault(fact, List.of())) {
                if (fact instanceof FinalField field && field.mayStore()
                        && !edge.loops().containsAll(loops)) {
                    continue;
                }
                if (current == null) {
                    current = graph.get();
                }
                if (holds(current, edge, block)) {
                    omitted.add(new Omitted(fact, edge, block, position));
                    return true;
                }
            }
        }
        return false;
    }

    /** Checks every omitted null check on the finished graph and its field stores. */
    void verify(Graph graph, List<Store> stores, String function) {
        for (Omitted omission : omitted) {
            boolean stored = omission.fact() instanceof FinalField field && stores.stream()
                    .filter(store -> store.owner().equals(field.owner())
                            && store.name().equals(field.name()))
                    .anyMatch(store -> graph.storeBetween(omission.edge().target(),
                            omission.block(), omission.position(), store));
            if (stored || !holds(graph, omission.edge(), omission.block())) {
                throw new IllegalStateException("null check omitted in block '" + omission.block()
                        + "' of " + function + " without a dominating null test");
            }
        }
    }

    private static boolean holds(Graph graph, Edge edge, String block) {
        return graph.onlyEdge(edge.test(), edge.target()) && graph.dominates(edge.target(), block);
    }

    /** The reference, the references it was converted from, and the final fields they load. */
    private List<Object> facts(IrOperand reference) {
        List<Object> result = new ArrayList<>();
        Set<IrOperand> seen = new HashSet<>();
        for (IrOperand value = reference; value != null && seen.add(value);
             value = conversions.get(value)) {
            result.add(value);
            FinalField field = finalFieldLoads.get(value);
            if (field != null) {
                result.add(field);
            }
        }
        return result;
    }

    private static List<IrOperand> union(List<IrOperand> first, List<IrOperand> second) {
        List<IrOperand> result = new ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }
}
