// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.BooleanLiteralExpression;
import ironwood.compiler.ast.BreakStatement;
import ironwood.compiler.ast.ContinueStatement;
import ironwood.compiler.ast.DeferStatement;
import ironwood.compiler.ast.DeferredFreeStatement;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EmptyStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.EnumConstantInitialization;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.LocalClassDeclaration;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.SuperConstructorInvocation;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchRuleThrow;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;

import java.util.List;
import java.util.function.Predicate;

/**
 * The routes lowering certainly takes out of source statements (D302). Lowering lowers
 * a deferred action or a finally block only on a route that reaches it: the normal
 * completion of the statements it protects, or an exception, return, break, continue or
 * yield that leaves them. Every other statement is lowered or reported unreachable.
 *
 * <p>This under-approximates FunctionAnalyzer's reachability, so a route found here is
 * one lowering takes. A throw statement counts only outside every try statement in the
 * searched code, where its exception edge reaches the protecting region; exceptions from
 * other code, the completion of catch bodies (a catch whose try body cannot throw is
 * analyzed without a route) and do-while exits reached through {@code continue} are not
 * counted. A transfer counts only outside finally blocks and when no finally block
 * between it and the searched code's boundary may fail to complete, since lowering
 * continues a transfer only through finally blocks that complete. Loops end without a
 * break only when their condition is not the literal {@code true}, as in lowering;
 * expressions never end a statement's normal completion.
 */
final class LoweredRoutes {
    private LoweredRoutes() {
    }

    /**
     * Whether lowering certainly takes a route out of {@code statements}, which a deferred
     * action protects: an exception thrown there reaches the deferred action's region.
     */
    static boolean leaves(List<Statement> statements) {
        return completes(statements) || transfersOut(statements, true);
    }

    /**
     * Whether lowering certainly lowers the finally block of {@code statement}: on the try
     * body's normal completion, on a transfer out of the body or of a catch body, which
     * lowering analyzes even when the try body cannot throw, or on an exception thrown in
     * a body that no catch clause can intercept.
     */
    static boolean reachesFinally(TryStatement statement) {
        List<Statement> body = statement.body().statements();
        return completes(body) || transfersOut(body, statement.catches().isEmpty())
                || statement.catches().stream()
                .anyMatch(caught -> transfersOut(caught.body().statements(), false));
    }

    private static boolean completes(List<Statement> statements) {
        return statements.stream().allMatch(LoweredRoutes::completes);
    }

    private static boolean completes(Statement statement) {
        if (statement instanceof Block block) {
            return completes(block.statements());
        }
        if (statement instanceof IfStatement conditional) {
            return completes(conditional.thenBranch())
                    || conditional.elseBranch().map(LoweredRoutes::completes).orElse(true);
        }
        if (statement instanceof WhileStatement loop) {
            return !isLiteralTrue(loop.condition()) || breaksOut(List.of(loop.body()));
        }
        if (statement instanceof DoWhileStatement loop) {
            return !isLiteralTrue(loop.condition()) && completes(loop.body())
                    || breaksOut(List.of(loop.body()));
        }
        if (statement instanceof ForStatement loop) {
            return loop.condition().filter(condition -> !isLiteralTrue(condition)).isPresent()
                    || breaksOut(List.of(loop.body()));
        }
        if (statement instanceof LabeledStatement labeled) {
            return completes(labeled.body()) || anyTransfer(List.of(labeled.body()), transfer ->
                    !transfer.inside() && transfer.statement() instanceof BreakStatement exit
                            && exit.label().filter(labeled.label()::equals).isPresent());
        }
        if (statement instanceof SwitchStatement switched) {
            List<Statement> statements = switched.groups().stream()
                    .flatMap(group -> group.statements().stream()).toList();
            return breaksOut(statements) || !switched.groups().isEmpty()
                    && completes(switched.groups().getLast().statements());
        }
        if (statement instanceof ModernSwitchStatement switched) {
            List<Statement> blocks = switched.rules().stream()
                    .filter(rule -> rule.body() instanceof SwitchRuleBlock)
                    .map(rule -> (Statement) ((SwitchRuleBlock) rule.body()).block()).toList();
            return breaksOut(blocks) || switched.rules().stream().anyMatch(rule ->
                    rule.body() instanceof SwitchRuleExpression
                            || rule.body() instanceof SwitchRuleBlock block && completes(block.block()));
        }
        if (statement instanceof TryStatement guarded) {
            return completes(guarded.body())
                    && guarded.finallyBlock().map(LoweredRoutes::completes).orElse(true);
        }
        // Lowering always completes these; any other statement may not complete.
        return statement instanceof LocalVariableDeclaration || statement instanceof ExpressionStatement
                || statement instanceof AssignmentStatement || statement instanceof FreeStatement
                || statement instanceof DeferStatement || statement instanceof DeferredFreeStatement
                || statement instanceof EnhancedForStatement || statement instanceof EmptyStatement
                || statement instanceof LocalClassDeclaration || statement instanceof EnumConstantInitialization
                || statement instanceof SuperConstructorInvocation
                || statement instanceof ThisConstructorInvocation;
    }

    /** Whether an unlabeled break in {@code statements} leaves the loop or switch that holds them. */
    private static boolean breaksOut(List<Statement> statements) {
        return anyTransfer(statements, transfer -> !transfer.inside()
                && transfer.statement() instanceof BreakStatement exit && exit.label().isEmpty());
    }

    /** Whether a transfer in {@code statements} leaves them; throws count only when {@code throwsLeave}. */
    private static boolean transfersOut(List<Statement> statements, boolean throwsLeave) {
        return anyTransfer(statements, transfer -> !transfer.inside()
                && (throwsLeave || !(transfer.statement() instanceof ThrowStatement)));
    }

    /**
     * A return, yield, break, continue or throw, and whether its target lies inside the
     * searched code: for a throw, whether a try statement there may intercept it.
     */
    private record Transfer(Statement statement, boolean inside) {
    }

    /** The loops, switch statements, labels and try statements between a transfer and the boundary. */
    private record Targets(int loops, int breakables, List<String> labels, int tries) {
        private static final Targets NONE = new Targets(0, 0, List.of(), 0);

        private Targets loop() {
            return new Targets(loops + 1, breakables + 1, labels, tries);
        }

        private Targets breakable() {
            return new Targets(loops, breakables + 1, labels, tries);
        }

        private Targets label(String label) {
            List<String> extended = new java.util.ArrayList<>(labels);
            extended.add(label);
            return new Targets(loops, breakables, List.copyOf(extended), tries);
        }

        private Targets guarded() {
            return new Targets(loops, breakables, labels, tries + 1);
        }
    }

    private static boolean anyTransfer(List<Statement> statements, Predicate<Transfer> test) {
        return statements.stream().anyMatch(statement -> anyTransfer(statement, Targets.NONE, test));
    }

    private static boolean anyTransfer(Statement statement, Targets targets, Predicate<Transfer> test) {
        if (statement instanceof ReturnStatement || statement instanceof YieldStatement) {
            // A yield leaves the enclosing switch expression, which lies outside any
            // statements searched here because expressions are not searched.
            return test.test(new Transfer(statement, false));
        }
        if (statement instanceof ThrowStatement) {
            return test.test(new Transfer(statement, targets.tries() > 0));
        }
        if (statement instanceof BreakStatement exit) {
            return test.test(new Transfer(statement, exit.label()
                    .map(targets.labels()::contains).orElse(targets.breakables() > 0)));
        }
        if (statement instanceof ContinueStatement next) {
            return test.test(new Transfer(statement, next.label()
                    .map(targets.labels()::contains).orElse(targets.loops() > 0)));
        }
        if (statement instanceof Block block) {
            return block.statements().stream().anyMatch(child -> anyTransfer(child, targets, test));
        }
        if (statement instanceof IfStatement conditional) {
            return anyTransfer(conditional.thenBranch(), targets, test) || conditional.elseBranch()
                    .map(branch -> anyTransfer(branch, targets, test)).orElse(false);
        }
        if (statement instanceof WhileStatement loop) {
            return anyTransfer(loop.body(), targets.loop(), test);
        }
        if (statement instanceof DoWhileStatement loop) {
            return anyTransfer(loop.body(), targets.loop(), test);
        }
        if (statement instanceof ForStatement loop) {
            return anyTransfer(loop.body(), targets.loop(), test);
        }
        if (statement instanceof EnhancedForStatement loop) {
            return anyTransfer(loop.body(), targets.loop(), test);
        }
        if (statement instanceof LabeledStatement labeled) {
            return anyTransfer(labeled.body(), targets.label(labeled.label()), test);
        }
        if (statement instanceof SwitchStatement switched) {
            Targets inner = targets.breakable();
            return switched.groups().stream().flatMap(group -> group.statements().stream())
                    .anyMatch(child -> anyTransfer(child, inner, test));
        }
        if (statement instanceof ModernSwitchStatement switched) {
            Targets inner = targets.breakable();
            return switched.rules().stream().anyMatch(rule ->
                    rule.body() instanceof SwitchRuleBlock block && anyTransfer(block.block(), inner, test)
                            || rule.body() instanceof SwitchRuleThrow thrown
                            && anyTransfer(thrown.statement(), inner, test));
        }
        if (statement instanceof TryStatement guarded) {
            // A finally block that may not complete stops every transfer through it,
            // and its own statements run only on routes that reach it.
            if (guarded.finallyBlock().isPresent() && !completes(guarded.finallyBlock().orElseThrow())) {
                return false;
            }
            Targets inner = targets.guarded();
            return anyTransfer(guarded.body(), inner, test) || guarded.catches().stream()
                    .anyMatch(caught -> anyTransfer(caught.body(), inner, test));
        }
        return false;
    }

    private static boolean isLiteralTrue(Expression condition) {
        return condition instanceof BooleanLiteralExpression literal && literal.value();
    }
}
