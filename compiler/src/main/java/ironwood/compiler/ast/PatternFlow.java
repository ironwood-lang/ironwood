// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Java-shaped definite-match facts for named instanceof pattern variables. */
public final class PatternFlow {
    private PatternFlow() {
    }

    public static Result analyze(Expression expression) {
        if (expression instanceof InstanceOfExpression typeTest && typeTest.binding().isPresent()) {
            return new Result(List.of(new Binding(typeTest, typeTest.binding().orElseThrow())),
                    List.of(), List.of());
        }
        if (expression instanceof UnaryExpression unary
                && unary.operator() == UnaryOperator.NOT) {
            Result operand = analyze(unary.operand());
            return new Result(operand.whenFalse(), operand.whenTrue(), operand.conflicts());
        }
        if (expression instanceof BinaryExpression binary
                && (binary.operator() == BinaryOperator.LOGICAL_AND
                || binary.operator() == BinaryOperator.LOGICAL_OR)) {
            Result left = analyze(binary.left());
            Result right = analyze(binary.right());
            List<Conflict> conflicts = new ArrayList<>(left.conflicts());
            conflicts.addAll(right.conflicts());
            conflicts.addAll(overlap(left.whenTrue(), right.whenTrue()));
            conflicts.addAll(overlap(left.whenFalse(), right.whenFalse()));
            if (binary.operator() == BinaryOperator.LOGICAL_AND) {
                return new Result(union(left.whenTrue(), right.whenTrue()), List.of(), conflicts);
            }
            return new Result(List.of(), union(left.whenFalse(), right.whenFalse()), conflicts);
        }
        if (expression instanceof ConditionalExpression conditional) {
            Result condition = analyze(conditional.condition());
            Result whenTrue = analyze(conditional.whenTrue());
            Result whenFalse = analyze(conditional.whenFalse());
            List<Conflict> conflicts = new ArrayList<>(condition.conflicts());
            conflicts.addAll(whenTrue.conflicts());
            conflicts.addAll(whenFalse.conflicts());
            conflicts.addAll(overlap(condition.whenTrue(), whenFalse.whenTrue()));
            conflicts.addAll(overlap(condition.whenTrue(), whenFalse.whenFalse()));
            conflicts.addAll(overlap(condition.whenFalse(), whenTrue.whenTrue()));
            conflicts.addAll(overlap(condition.whenFalse(), whenTrue.whenFalse()));
            conflicts.addAll(overlap(whenTrue.whenTrue(), whenFalse.whenTrue()));
            conflicts.addAll(overlap(whenTrue.whenFalse(), whenFalse.whenFalse()));
            return new Result(List.of(), List.of(), conflicts);
        }
        if (expression instanceof SwitchExpression switched) {
            List<Conflict> conflicts = new ArrayList<>(analyze(switched.selector()).conflicts());
            if (switched.arrowRules()) {
                switched.rules().forEach(rule -> {
                    if (rule.body() instanceof SwitchRuleExpression result) {
                        conflicts.addAll(analyze(result.expression()).conflicts());
                    }
                });
            }
            return new Result(List.of(), List.of(), conflicts);
        }
        return Result.EMPTY;
    }

    public static List<Binding> bindingsForRightOperand(BinaryExpression expression) {
        Result left = analyze(expression.left());
        return expression.operator() == BinaryOperator.LOGICAL_AND
                ? left.whenTrue() : left.whenFalse();
    }

    public static boolean canCompleteNormally(Statement statement) {
        if (statement instanceof ReturnStatement || statement instanceof ThrowStatement
                || statement instanceof YieldStatement
                || statement instanceof BreakStatement || statement instanceof ContinueStatement) {
            return false;
        }
        if (statement instanceof Block block) {
            return block.statements().isEmpty()
                    || canCompleteNormally(block.statements().getLast());
        }
        if (statement instanceof IfStatement conditional
                && conditional.elseBranch().isPresent()) {
            return canCompleteNormally(conditional.thenBranch())
                    || canCompleteNormally(conditional.elseBranch().orElseThrow());
        }
        if (statement instanceof TryStatement guarded) {
            if (guarded.finallyBlock().isPresent()
                    && !canCompleteNormally(guarded.finallyBlock().orElseThrow())) {
                return false;
            }
            return canCompleteNormally(guarded.body()) || guarded.catches().stream()
                    .anyMatch(caught -> canCompleteNormally(caught.body()));
        }
        if (statement instanceof LabeledStatement labeled) {
            return canCompleteNormally(labeled.body())
                    || containsBreakToLabel(labeled.body(), labeled.label());
        }
        return true;
    }

    public static boolean containsBreakForCurrentLoop(Statement statement) {
        if (statement instanceof BreakStatement) {
            return true;
        }
        if (statement instanceof WhileStatement || statement instanceof DoWhileStatement
                || statement instanceof ForStatement || statement instanceof EnhancedForStatement
                || statement instanceof SwitchStatement
                || statement instanceof ModernSwitchStatement) {
            return false;
        }
        if (statement instanceof LabeledStatement labeled) {
            return containsBreakForCurrentLoop(labeled.body());
        }
        if (statement instanceof Block block) {
            return block.statements().stream().anyMatch(PatternFlow::containsBreakForCurrentLoop);
        }
        if (statement instanceof IfStatement conditional) {
            return containsBreakForCurrentLoop(conditional.thenBranch())
                    || conditional.elseBranch()
                    .map(PatternFlow::containsBreakForCurrentLoop).orElse(false);
        }
        if (statement instanceof TryStatement guarded) {
            return containsBreakForCurrentLoop(guarded.body())
                    || guarded.catches().stream()
                    .anyMatch(caught -> containsBreakForCurrentLoop(caught.body()))
                    || guarded.finallyBlock()
                    .map(PatternFlow::containsBreakForCurrentLoop).orElse(false);
        }
        return false;
    }

    private static boolean containsBreakToLabel(Statement statement, String label) {
        if (statement instanceof BreakStatement transfer) {
            return transfer.label().filter(label::equals).isPresent();
        }
        if (statement instanceof LabeledStatement labeled) {
            if (labeled.label().equals(label)) {
                return false;
            }
            return containsBreakToLabel(labeled.body(), label);
        }
        if (statement instanceof Block block) {
            return block.statements().stream()
                    .anyMatch(child -> containsBreakToLabel(child, label));
        }
        if (statement instanceof IfStatement conditional) {
            return containsBreakToLabel(conditional.thenBranch(), label)
                    || conditional.elseBranch()
                    .map(branch -> containsBreakToLabel(branch, label)).orElse(false);
        }
        if (statement instanceof WhileStatement loop) {
            return containsBreakToLabel(loop.body(), label);
        }
        if (statement instanceof DoWhileStatement loop) {
            return containsBreakToLabel(loop.body(), label);
        }
        if (statement instanceof ForStatement loop) {
            return containsBreakToLabel(loop.body(), label);
        }
        if (statement instanceof EnhancedForStatement loop) {
            return containsBreakToLabel(loop.body(), label);
        }
        if (statement instanceof SwitchStatement switched) {
            return switched.groups().stream().flatMap(group -> group.statements().stream())
                    .anyMatch(child -> containsBreakToLabel(child, label));
        }
        if (statement instanceof ModernSwitchStatement switched) {
            return switched.rules().stream().anyMatch(rule -> {
                if (rule.body() instanceof SwitchRuleBlock block) {
                    return containsBreakToLabel(block.block(), label);
                }
                if (rule.body() instanceof SwitchRuleThrow thrown) {
                    return containsBreakToLabel(thrown.statement(), label);
                }
                return false;
            });
        }
        if (statement instanceof TryStatement guarded) {
            return containsBreakToLabel(guarded.body(), label)
                    || guarded.catches().stream()
                    .anyMatch(caught -> containsBreakToLabel(caught.body(), label))
                    || guarded.finallyBlock()
                    .map(cleanup -> containsBreakToLabel(cleanup, label)).orElse(false);
        }
        return false;
    }

    private static List<Binding> union(List<Binding> first, List<Binding> second) {
        List<Binding> result = new ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    private static List<Conflict> overlap(List<Binding> first, List<Binding> second) {
        Map<String, Binding> names = new LinkedHashMap<>();
        first.forEach(binding -> names.putIfAbsent(binding.variable().name(), binding));
        List<Conflict> conflicts = new ArrayList<>();
        for (Binding binding : second) {
            Binding earlier = names.get(binding.variable().name());
            if (earlier != null && earlier.expression() != binding.expression()) {
                conflicts.add(new Conflict(earlier, binding));
            }
        }
        return List.copyOf(conflicts);
    }

    public record Binding(InstanceOfExpression expression, TypePatternBinding variable) {
    }

    public record Conflict(Binding earlier, Binding later) {
        public SourceSpan span() {
            return later.variable().nameSpan();
        }
    }

    public record Result(List<Binding> whenTrue, List<Binding> whenFalse,
                         List<Conflict> conflicts) {
        private static final Result EMPTY = new Result(List.of(), List.of(), List.of());

        public Result {
            whenTrue = List.copyOf(whenTrue);
            whenFalse = List.copyOf(whenFalse);
            conflicts = List.copyOf(conflicts);
        }
    }
}
