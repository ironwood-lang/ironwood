// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.ArrayAccessExpression;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.AssignmentExpression;
import ironwood.compiler.ast.AssignmentOperator;
import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.BinaryOperator;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.BooleanLiteralExpression;
import ironwood.compiler.ast.BreakStatement;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.CatchClause;
import ironwood.compiler.ast.CharacterLiteralExpression;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.ContinueStatement;
import ironwood.compiler.ast.DeferStatement;
import ironwood.compiler.ast.DeferredFreeStatement;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EmptyStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.EnumConstantInitialization;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.FloatingLiteralExpression;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.InstanceOfExpression;
import ironwood.compiler.ast.IntegerLiteralExpression;
import ironwood.compiler.ast.InterfaceSuperExpression;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.LocalClassDeclaration;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.NullLiteralExpression;
import ironwood.compiler.ast.QualifiedSuperConstructorExpression;
import ironwood.compiler.ast.QualifiedThisExpression;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.StringLiteralExpression;
import ironwood.compiler.ast.SuperConstructorInvocation;
import ironwood.compiler.ast.SuperExpression;
import ironwood.compiler.ast.SwitchExpression;
import ironwood.compiler.ast.SwitchGroup;
import ironwood.compiler.ast.SwitchLabel;
import ironwood.compiler.ast.SwitchRule;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchRuleThrow;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ast.ThisExpression;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UnaryOperator;
import ironwood.compiler.ast.UpdateExpression;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a function body frees a value that holds a load of a field through
 * {@code this}, where lowering is certain to lower the free (D307). Without the field's
 * ownership lowering rejects such a free, so the field's proof is needed (D297).
 *
 * <p>A forward must-analysis tracks the locals that hold a field load on every path
 * lowering takes to a point. A value holds one when it is a load of the field through
 * {@code this}, possibly under casts, a local that holds one, a conditional whose
 * reachable branches both hold one, a switch expression whose arms all yield one, or
 * an assignment of one; any other write to a local holds none. A pattern binding holds
 * its operand's value where its test is true (D308). Conditions split the state as
 * lowering branches: the right operand of {@code &&} runs only when the left one is
 * true, so code the whole condition guards sees its writes. Only a loop condition that is the literal {@code true} ends a path, as in
 * lowering. Loops iterate to a fixed point, and frees inside them count only on the
 * pass with it. Break, continue and yield carry their states to their targets, less
 * what finally blocks on the way may write. A finally block's check starts from the
 * meet of every state its try statement passed through, and a catch handler from the
 * states where lowering may add an exception edge (D309). An expression this analysis
 * does not know is taken to write every local.
 */
final class HeldFieldLoads {
    private final String field;
    private final boolean staticFunction;
    /** Pattern binding names seen so far: locals that may shadow the field (D308). */
    private final Set<String> bindings = new HashSet<>();
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Deque<Region> regions = new ArrayDeque<>();
    /** Deferred actions registered in enclosing blocks, which may throw where they run. */
    private int pendingCleanups;
    /** False while a loop iterates to its fixed point. */
    private boolean recording = true;
    private int unreachedFinallies;
    private boolean found;

    private HeldFieldLoads(String field, boolean staticFunction) {
        this.field = field;
        this.staticFunction = staticFunction;
    }

    /** Whether {@code body} frees a value holding a load of {@code field} through {@code this}. */
    static boolean freesHeldLoad(String field, boolean staticFunction, List<String> parameters, Block body) {
        HeldFieldLoads analysis = new HeldFieldLoads(field, staticFunction);
        analysis.block(body.statements(), Set.of(), new HashSet<>(parameters));
        return analysis.found;
    }

    /**
     * A value's holding and whether it is the null literal, with the state after
     * evaluating it; a null state is unreachable.
     */
    private record Value(Set<String> held, boolean holds, boolean isNull) {
        private Value(Set<String> held, boolean holds) {
            this(held, holds, false);
        }
    }

    /**
     * The marker of a local that holds the null literal on every path (D309): lowering
     * never frees it, so a catch handler that lowering starts from the try body's entry
     * state may treat it like a field load. Identifiers never contain a colon.
     */
    private static final String NULL = "null:";

    /** The states after a condition when it is true and when it is false. */
    private record Split(Set<String> whenTrue, Set<String> whenFalse) {
    }

    private interface Frame {
    }

    /** A loop, switch statement or labeled statement that break or continue can target. */
    private static final class Target implements Frame {
        private final String label;
        private final boolean loop;
        private final boolean switchStatement;
        private Set<String> breaks;
        private Set<String> continues;

        private Target(String label, boolean loop, boolean switchStatement) {
            this.label = label;
            this.loop = loop;
            this.switchStatement = switchStatement;
        }
    }

    /** A finally block between a transfer and its target, with the locals it may write. */
    private record Cleanup(Set<String> writes) implements Frame {
    }

    /** A switch expression, with the meet of the states and holdings its arms yield. */
    private static final class Results implements Frame {
        private Set<String> held;
        private boolean holds = true;
    }

    /**
     * A try statement's body or catch handlers, with its entry state, the meet of every
     * state seen in it, and the meet of the states where lowering may add an exception
     * edge (D309).
     */
    private static final class Region {
        private final Set<String> entry;
        private Set<String> all;
        private Set<String> thrown;

        private Region(Set<String> entry) {
            this.entry = entry;
            this.all = entry;
        }
    }

    private Set<String> block(List<Statement> statements, Set<String> held, Set<String> scope) {
        int registered = 0;
        for (int index = 0; index < statements.size(); index++) {
            Statement statement = statements.get(index);
            if (statement instanceof DeferStatement || statement instanceof DeferredFreeStatement) {
                registered++;
                pendingCleanups++;
            }
            if (statement instanceof DeferredFreeStatement deferred) {
                // Lowering frees the target's value at every route out of the rest of the
                // block (D302); with no write to it there, that value is this one.
                String name = deferred.target().name();
                List<Statement> rest = statements.subList(index + 1, statements.size());
                if (held != null && isLocal(name, scope) && held.contains(name) && recording
                        && unreachedFinallies == 0 && LoweredRoutes.leaves(rest)
                        && rest.stream().noneMatch(later -> writes(later, name))) {
                    found = true;
                }
                continue;
            }
            held = statement(statement, held, scope);
        }
        if (registered > 0) {
            // The block's deferred actions run where it completes.
            thrown(held);
            pendingCleanups -= registered;
        }
        return held;
    }

    private Set<String> statement(Statement statement, Set<String> held, Set<String> scope) {
        if (held == null) {
            return null;
        }
        note(held);
        if (statement instanceof Block block) {
            return block(block.statements(), held, new HashSet<>(scope));
        }
        if (statement instanceof LocalVariableDeclaration local) {
            Value value = expression(local.initializer(), held, scope);
            scope.add(local.name());
            Set<String> out = write(value.held(), local.name(), value.holds(), value.isNull());
            note(out);
            return out;
        }
        if (statement instanceof ExpressionStatement expression) {
            return expression(expression.expression(), held, scope).held();
        }
        if (statement instanceof AssignmentStatement assignment) {
            return assign(assignment.target(), assignment.value(), true, held, scope).held();
        }
        if (statement instanceof FreeStatement free) {
            Value value = expression(free.value(), held, scope);
            if (value.holds() && recording && unreachedFinallies == 0) {
                found = true;
            }
            thrown(value.held());
            return value.held();
        }
        if (statement instanceof DeferStatement deferred) {
            // Lowering evaluates the call's operands where the defer stands.
            return expression(deferred.call(), held, scope).held();
        }
        if (statement instanceof IfStatement conditional) {
            Split split = condition(conditional.condition(), held, scope);
            Set<String> whenTrue = statement(conditional.thenBranch(), split.whenTrue(), new HashSet<>(scope));
            Set<String> whenFalse = conditional.elseBranch()
                    .map(branch -> statement(branch, split.whenFalse(), new HashSet<>(scope)))
                    .orElse(split.whenFalse());
            return meet(whenTrue, whenFalse);
        }
        if (statement instanceof WhileStatement loop) {
            return whileLoop(loop, null, held, scope);
        }
        if (statement instanceof DoWhileStatement loop) {
            return doLoop(loop, null, held, scope);
        }
        if (statement instanceof ForStatement loop) {
            return forLoop(loop, null, held, scope);
        }
        if (statement instanceof EnhancedForStatement loop) {
            return enhancedFor(loop, null, held, scope);
        }
        if (statement instanceof LabeledStatement labeled) {
            return labeled(labeled, held, scope);
        }
        if (statement instanceof SwitchStatement switched) {
            return switchStatement(switched, held, scope);
        }
        if (statement instanceof ModernSwitchStatement switched) {
            return modernSwitch(switched, held, scope);
        }
        if (statement instanceof TryStatement guarded) {
            return tryStatement(guarded, held, scope);
        }
        if (statement instanceof ReturnStatement returned) {
            Set<String> out = returned.value().map(value -> expression(value, held, scope).held()).orElse(held);
            transferred(out);
            return null;
        }
        if (statement instanceof ThrowStatement throwing) {
            thrown(expression(throwing.value(), held, scope).held());
            return null;
        }
        if (statement instanceof BreakStatement || statement instanceof ContinueStatement) {
            transferred(held);
            jump(statement, held);
            return null;
        }
        if (statement instanceof YieldStatement yielded) {
            Value value = expression(yielded.value(), held, scope);
            transferred(value.held());
            yieldTo(value);
            return null;
        }
        if (statement instanceof EnumConstantInitialization initialization) {
            return thrown(sequence(initialization.constant().arguments(), held, scope));
        }
        if (statement instanceof SuperConstructorInvocation invocation) {
            List<Expression> operands = new ArrayList<>();
            invocation.enclosingInstance().ifPresent(operands::add);
            operands.addAll(invocation.arguments());
            return thrown(sequence(operands, held, scope));
        }
        if (statement instanceof ThisConstructorInvocation invocation) {
            return thrown(sequence(invocation.arguments(), held, scope));
        }
        if (statement instanceof EmptyStatement || statement instanceof LocalClassDeclaration
                || statement instanceof DeferredFreeStatement) {
            return held;
        }
        // A statement this analysis does not know may write any local.
        return Set.of();
    }

    private Set<String> whileLoop(WhileStatement loop, String label, Set<String> held, Set<String> scope) {
        boolean outer = recording;
        recording = false;
        Set<String> header = held;
        while (true) {
            Target target = new Target(label, true, false);
            frames.push(target);
            Split split = condition(loop.condition(), header, scope);
            Set<String> end = statement(loop.body(), split.whenTrue(), new HashSet<>(scope));
            frames.pop();
            Set<String> next = meet(held, meet(end, target.continues));
            if (Objects.equals(next, header)) {
                break;
            }
            header = next;
        }
        recording = outer;
        Target target = new Target(label, true, false);
        frames.push(target);
        Split split = condition(loop.condition(), header, scope);
        statement(loop.body(), split.whenTrue(), new HashSet<>(scope));
        frames.pop();
        return meet(isLiteralTrue(loop.condition()) ? null : split.whenFalse(), target.breaks);
    }

    private Set<String> doLoop(DoWhileStatement loop, String label, Set<String> held, Set<String> scope) {
        boolean outer = recording;
        recording = false;
        Set<String> header = held;
        while (true) {
            Target target = new Target(label, true, false);
            frames.push(target);
            Set<String> end = statement(loop.body(), header, new HashSet<>(scope));
            frames.pop();
            Split split = condition(loop.condition(), meet(end, target.continues), scope);
            Set<String> next = meet(held, split.whenTrue());
            if (Objects.equals(next, header)) {
                break;
            }
            header = next;
        }
        recording = outer;
        Target target = new Target(label, true, false);
        frames.push(target);
        Set<String> end = statement(loop.body(), header, new HashSet<>(scope));
        frames.pop();
        Split split = condition(loop.condition(), meet(end, target.continues), scope);
        return meet(isLiteralTrue(loop.condition()) ? null : split.whenFalse(), target.breaks);
    }

    private Set<String> forLoop(ForStatement loop, String label, Set<String> held, Set<String> scope) {
        Set<String> loopScope = new HashSet<>(scope);
        Set<String> entry = loop.initializer().map(initializer -> statement(initializer, held, loopScope))
                .orElse(held);
        boolean endless = loop.condition().map(HeldFieldLoads::isLiteralTrue).orElse(true);
        boolean outer = recording;
        recording = false;
        Set<String> header = entry;
        while (true) {
            Target target = new Target(label, true, false);
            Split split = forPass(loop, header, loopScope, target);
            Set<String> next = meet(entry, sequence(loop.updates(), meet(split.whenTrue(), target.continues),
                    loopScope));
            if (Objects.equals(next, header)) {
                break;
            }
            header = next;
        }
        recording = outer;
        Target target = new Target(label, true, false);
        Split split = forPass(loop, header, loopScope, target);
        sequence(loop.updates(), meet(split.whenTrue(), target.continues), loopScope);
        return meet(endless ? null : split.whenFalse(), target.breaks);
    }

    /** One pass of a for loop from {@code header}: the body's end as true, the exit as false. */
    private Split forPass(ForStatement loop, Set<String> header, Set<String> scope, Target target) {
        Split test = loop.condition().map(condition -> condition(condition, header, scope))
                .orElse(new Split(header, header));
        frames.push(target);
        Set<String> end = statement(loop.body(), test.whenTrue(), new HashSet<>(scope));
        frames.pop();
        return new Split(end, test.whenFalse());
    }

    private Set<String> enhancedFor(EnhancedForStatement loop, String label, Set<String> held, Set<String> scope) {
        Set<String> entry = expression(loop.iterable(), held, scope).held();
        Set<String> loopScope = new HashSet<>(scope);
        loopScope.add(loop.variableName());
        boolean outer = recording;
        recording = false;
        Set<String> header = entry;
        while (true) {
            Target target = new Target(label, true, false);
            frames.push(target);
            // Each iteration steps the iterator, which may throw.
            thrown(header);
            Set<String> end = statement(loop.body(), write(header, loop.variableName(), false),
                    new HashSet<>(loopScope));
            frames.pop();
            Set<String> next = meet(entry, meet(end, target.continues));
            if (Objects.equals(next, header)) {
                break;
            }
            header = next;
        }
        recording = outer;
        Target target = new Target(label, true, false);
        frames.push(target);
        thrown(header);
        statement(loop.body(), write(header, loop.variableName(), false), new HashSet<>(loopScope));
        frames.pop();
        return meet(header, target.breaks);
    }

    private Set<String> labeled(LabeledStatement labeled, Set<String> held, Set<String> scope) {
        Statement body = labeled.body();
        if (body instanceof WhileStatement loop) {
            return whileLoop(loop, labeled.label(), held, scope);
        }
        if (body instanceof DoWhileStatement loop) {
            return doLoop(loop, labeled.label(), held, scope);
        }
        if (body instanceof ForStatement loop) {
            return forLoop(loop, labeled.label(), held, scope);
        }
        if (body instanceof EnhancedForStatement loop) {
            return enhancedFor(loop, labeled.label(), held, scope);
        }
        Target target = new Target(labeled.label(), false, false);
        frames.push(target);
        Set<String> end = statement(body, held, scope);
        frames.pop();
        return meet(end, target.breaks);
    }

    private Set<String> switchStatement(SwitchStatement switched, Set<String> held, Set<String> scope) {
        Set<String> dispatched = thrown(expression(switched.selector(), held, scope).held());
        Set<String> switchScope = new HashSet<>(scope);
        Target target = new Target(null, false, true);
        frames.push(target);
        Set<String> fallthrough = null;
        boolean hasDefault = false;
        for (SwitchGroup group : switched.groups()) {
            hasDefault |= group.labels().stream().anyMatch(SwitchLabel::isDefault);
            fallthrough = block(group.statements(), meet(dispatched, fallthrough), switchScope);
        }
        frames.pop();
        Set<String> out = meet(fallthrough, target.breaks);
        return hasDefault ? out : meet(out, dispatched);
    }

    private Set<String> modernSwitch(ModernSwitchStatement switched, Set<String> held, Set<String> scope) {
        Set<String> dispatched = thrown(expression(switched.selector(), held, scope).held());
        Target target = new Target(null, false, true);
        frames.push(target);
        Set<String> out = null;
        boolean hasDefault = false;
        for (SwitchRule rule : switched.rules()) {
            hasDefault |= rule.labels().stream().anyMatch(SwitchLabel::isDefault);
            if (rule.body() instanceof SwitchRuleExpression result) {
                out = meet(out, expression(result.expression(), dispatched, scope).held());
            } else if (rule.body() instanceof SwitchRuleBlock block) {
                out = meet(out, block(block.block().statements(), dispatched, new HashSet<>(scope)));
            } else if (rule.body() instanceof SwitchRuleThrow thrown) {
                expression(thrown.statement().value(), dispatched, scope);
            } else {
                out = meet(out, Set.of());
            }
        }
        frames.pop();
        out = meet(out, target.breaks);
        return hasDefault ? out : meet(out, dispatched);
    }

    private Set<String> tryStatement(TryStatement guarded, Set<String> held, Set<String> scope) {
        Optional<Block> cleanup = guarded.finallyBlock();
        if (cleanup.isPresent()) {
            // A finally block whose writes are unknown keeps no local held across it.
            frames.push(new Cleanup(writtenLocals(cleanup.orElseThrow())));
        }
        Region body = new Region(held);
        regions.push(body);
        Set<String> bodyEnd = block(guarded.body().statements(), held, new HashSet<>(scope));
        regions.pop();
        // A handler starts from any state the body passed through.
        Region handlers = new Region(null);
        regions.push(handlers);
        Set<String> normal = bodyEnd;
        Set<String> handlerEntry = handlerEntry(body);
        for (CatchClause caught : guarded.catches()) {
            Set<String> handlerScope = new HashSet<>(scope);
            handlerScope.add(caught.variableName());
            normal = meet(normal, block(caught.body().statements(),
                    write(handlerEntry, caught.variableName(), false), handlerScope));
        }
        regions.pop();
        if (cleanup.isEmpty()) {
            return normal;
        }
        frames.pop();
        Block finallyBlock = cleanup.orElseThrow();
        // The finally block runs on every route out, from any state the try statement
        // passed through; lowering lowers it only on a route that reaches it (D302).
        boolean reached = LoweredRoutes.reachesFinally(guarded);
        if (!reached) unreachedFinallies++;
        block(finallyBlock.statements(), meet(body.all, handlers.all), new HashSet<>(scope));
        if (!reached) unreachedFinallies--;
        boolean outer = recording;
        recording = false;
        Set<String> out = block(finallyBlock.statements(), normal, new HashSet<>(scope));
        recording = outer;
        return out;
    }

    /**
     * The state a catch handler starts from (D309). Lowering starts a handler from the
     * states at the try body's exception edges, which arise only where this analysis
     * records a possible throw, or, when no edge reaches it, from the body's entry state.
     * A local therefore holds a field load there when it holds one at every recorded
     * point and, at the entry, holds one or is the null literal, which lowering never
     * frees either; with no recorded point the handler starts from the entry state.
     */
    private static Set<String> handlerEntry(Region body) {
        if (body.thrown == null || body.entry == null) {
            return body.entry;
        }
        Set<String> result = new HashSet<>();
        for (String marker : body.thrown) {
            if (body.entry.contains(marker)
                    || !marker.startsWith(NULL) && body.entry.contains(NULL + marker)) {
                result.add(marker);
            }
        }
        return result;
    }

    /** Carries a break or continue's state to its target, less what finally blocks write. */
    private void jump(Statement statement, Set<String> held) {
        boolean isBreak = statement instanceof BreakStatement;
        Optional<String> label = statement instanceof BreakStatement exit ? exit.label()
                : ((ContinueStatement) statement).label();
        Set<String> carried = held;
        for (Frame frame : frames) {
            if (frame instanceof Cleanup cleanup) {
                carried = without(carried, cleanup.writes());
            } else if (frame instanceof Target target && targets(target, isBreak, label)) {
                if (isBreak) {
                    target.breaks = meet(target.breaks, carried);
                } else {
                    target.continues = meet(target.continues, carried);
                }
                return;
            } else if (frame instanceof Results) {
                return;
            }
        }
    }

    private static boolean targets(Target target, boolean isBreak, Optional<String> label) {
        if (label.isPresent()) {
            return label.orElseThrow().equals(target.label) && (isBreak || target.loop);
        }
        return isBreak ? target.loop || target.switchStatement : target.loop;
    }

    private void yieldTo(Value value) {
        Set<String> carried = value.held();
        for (Frame frame : frames) {
            if (frame instanceof Cleanup cleanup) {
                carried = without(carried, cleanup.writes());
            } else if (frame instanceof Results results) {
                if (carried != null) {
                    results.held = meet(results.held, carried);
                    results.holds &= value.holds();
                }
                return;
            }
        }
    }

    private Value expression(Expression expression, Set<String> held, Set<String> scope) {
        if (held == null) {
            return new Value(null, false);
        }
        if (expression instanceof NameExpression name) {
            if (isLocal(name.name(), scope)) {
                return new Value(held, held.contains(name.name()), held.contains(NULL + name.name()));
            }
            if (!staticFunction && name.name().equals(field)) {
                return new Value(held, true);
            }
            // Another name may be a static field, whose class may need initializing.
            return new Value(thrown(held), false);
        }
        if (expression instanceof FieldAccessExpression access) {
            Value receiver = expression(access.receiver(), held, scope);
            if (access.receiver() instanceof ThisExpression) {
                return new Value(receiver.held(), access.fieldName().equals(field));
            }
            return new Value(thrown(receiver.held()), false);
        }
        if (expression instanceof NullLiteralExpression) {
            return new Value(held, false, true);
        }
        if (expression instanceof CastExpression cast) {
            Value operand = expression(cast.operand(), held, scope);
            return new Value(thrown(operand.held()), operand.holds(), operand.isNull());
        }
        if (expression instanceof AssignmentExpression assignment) {
            return assign(assignment.target(), assignment.value(),
                    assignment.operator() == AssignmentOperator.ASSIGN, held, scope);
        }
        if (expression instanceof ConditionalExpression conditional) {
            Split split = condition(conditional.condition(), held, scope);
            Value first = expression(conditional.whenTrue(), split.whenTrue(), scope);
            Value second = expression(conditional.whenFalse(), split.whenFalse(), scope);
            return new Value(meet(first.held(), second.held()),
                    (first.held() == null || first.holds()) && (second.held() == null || second.holds()));
        }
        if (isLogical(expression)) {
            Split split = condition(expression, held, scope);
            return new Value(meet(split.whenTrue(), split.whenFalse()), false);
        }
        if (expression instanceof SwitchExpression switched) {
            return switchExpression(switched, held, scope);
        }
        if (expression instanceof InstanceOfExpression test) {
            if (test.binding().isEmpty()) {
                return new Value(expression(test.operand(), held, scope).held(), false);
            }
            // Outside a condition the binding is not known to match.
            Split split = condition(test, held, scope);
            return new Value(meet(split.whenTrue(), split.whenFalse()), false);
        }
        if (expression instanceof UpdateExpression update) {
            if (update.target() instanceof NameExpression local && isLocal(local.name(), scope)) {
                Set<String> out = write(thrown(held), local.name(), false);
                note(out);
                return new Value(out, false);
            }
            return new Value(thrown(expression(update.target(), held, scope).held()), false);
        }
        List<Expression> operands = operands(expression);
        if (operands == null) {
            // An expression this analysis does not know may write any local.
            return new Value(Set.of(), false);
        }
        Set<String> after = sequence(operands, held, scope);
        return new Value(silent(expression) ? after : thrown(after), false);
    }

    private Value assign(Expression target, Expression value, boolean plain, Set<String> held,
                         Set<String> scope) {
        if (target instanceof NameExpression local && isLocal(local.name(), scope)) {
            Value written = expression(value, held, scope);
            boolean holds = plain && written.holds();
            boolean isNull = plain && written.isNull();
            // A compound assignment computes before it writes, which may throw.
            Set<String> before = plain ? written.held() : thrown(written.held());
            Set<String> out = write(before, local.name(), holds, isNull);
            note(out);
            return new Value(out, holds, isNull);
        }
        Set<String> state = held;
        boolean silent = plain && (target instanceof NameExpression name && name.name().equals(field)
                && !staticFunction || target instanceof FieldAccessExpression access
                && access.receiver() instanceof ThisExpression);
        if (target instanceof ArrayAccessExpression access) {
            state = expression(access.array(), state, scope).held();
            state = expression(access.index(), state, scope).held();
        } else if (target instanceof FieldAccessExpression access) {
            state = expression(access.receiver(), state, scope).held();
        }
        Value written = expression(value, state, scope);
        // A store through this to its field adds no exception edge; any other store may.
        Set<String> out = silent ? written.held() : thrown(written.held());
        return new Value(out, plain && written.holds(), plain && written.isNull());
    }

    private Value switchExpression(SwitchExpression switched, Set<String> held, Set<String> scope) {
        Set<String> dispatched = thrown(expression(switched.selector(), held, scope).held());
        Results results = new Results();
        frames.push(results);
        if (switched.arrowRules()) {
            for (SwitchRule rule : switched.rules()) {
                if (rule.body() instanceof SwitchRuleExpression result) {
                    yieldTo(expression(result.expression(), dispatched, scope));
                } else if (rule.body() instanceof SwitchRuleBlock block) {
                    block(block.block().statements(), dispatched, new HashSet<>(scope));
                } else if (rule.body() instanceof SwitchRuleThrow thrown) {
                    expression(thrown.statement().value(), dispatched, scope);
                } else {
                    yieldTo(new Value(Set.of(), false));
                }
            }
        } else {
            Set<String> groupScope = new HashSet<>(scope);
            Set<String> fallthrough = null;
            for (SwitchGroup group : switched.groups()) {
                fallthrough = block(group.statements(), meet(dispatched, fallthrough), groupScope);
            }
        }
        frames.pop();
        return new Value(results.held, results.held != null && results.holds);
    }

    private Split condition(Expression expression, Set<String> held, Set<String> scope) {
        if (held == null) {
            return new Split(null, null);
        }
        if (expression instanceof UnaryExpression unary && unary.operator() == UnaryOperator.NOT) {
            Split operand = condition(unary.operand(), held, scope);
            return new Split(operand.whenFalse(), operand.whenTrue());
        }
        if (expression instanceof BinaryExpression binary && binary.operator() == BinaryOperator.LOGICAL_AND) {
            Split left = condition(binary.left(), held, scope);
            Split right = condition(binary.right(), left.whenTrue(), scope);
            return new Split(right.whenTrue(), meet(left.whenFalse(), right.whenFalse()));
        }
        if (expression instanceof BinaryExpression binary && binary.operator() == BinaryOperator.LOGICAL_OR) {
            Split left = condition(binary.left(), held, scope);
            Split right = condition(binary.right(), left.whenFalse(), scope);
            return new Split(meet(left.whenTrue(), right.whenTrue()), right.whenFalse());
        }
        if (expression instanceof ConditionalExpression conditional) {
            Split test = condition(conditional.condition(), held, scope);
            Split first = condition(conditional.whenTrue(), test.whenTrue(), scope);
            Split second = condition(conditional.whenFalse(), test.whenFalse(), scope);
            return new Split(meet(first.whenTrue(), second.whenTrue()),
                    meet(first.whenFalse(), second.whenFalse()));
        }
        if (expression instanceof InstanceOfExpression test && test.binding().isPresent()) {
            // A matching test binds its operand's value, as a checked cast of it.
            Value operand = expression(test.operand(), held, scope);
            String binding = test.binding().orElseThrow().name();
            bindings.add(binding);
            Set<String> matched = write(operand.held(), binding, operand.holds());
            note(matched);
            return new Split(matched, write(operand.held(), binding, false));
        }
        Set<String> after = expression(expression, held, scope).held();
        return new Split(after, after);
    }

    /**
     * Whether {@code name} is a local where it is read: declared in scope, or a pattern
     * binding, which Java keeps from shadowing a local. A binding named like the field
     * makes later reads of the field count as reads of the binding, which hold a load
     * only if the binding did, and a read of the field always does.
     */
    private boolean isLocal(String name, Set<String> scope) {
        return scope.contains(name) || bindings.contains(name);
    }

    private static boolean isLogical(Expression expression) {
        return expression instanceof UnaryExpression unary && unary.operator() == UnaryOperator.NOT
                || expression instanceof BinaryExpression binary
                && (binary.operator() == BinaryOperator.LOGICAL_AND
                || binary.operator() == BinaryOperator.LOGICAL_OR);
    }

    private static boolean isLiteralTrue(Expression condition) {
        return condition instanceof BooleanLiteralExpression literal && literal.value();
    }

    /**
     * The operands an expression evaluates in order, for kinds that write no local
     * themselves, or null for a kind this analysis does not know.
     */
    private static List<Expression> operands(Expression expression) {
        List<Expression> operands = new ArrayList<>();
        if (expression instanceof BinaryExpression binary) {
            operands.add(binary.left());
            operands.add(binary.right());
        } else if (expression instanceof UnaryExpression unary) {
            operands.add(unary.operand());
        } else if (expression instanceof CallExpression call) {
            call.receiver().ifPresent(operands::add);
            operands.addAll(call.arguments());
        } else if (expression instanceof NewExpression creation) {
            creation.enclosingInstance().ifPresent(operands::add);
            operands.addAll(creation.arguments());
        } else if (expression instanceof QualifiedSuperConstructorExpression invocation) {
            operands.add(invocation.enclosingInstance());
            operands.addAll(invocation.arguments());
        } else if (expression instanceof ArrayCreationExpression creation) {
            creation.length().ifPresent(operands::add);
            creation.initializer().ifPresent(operands::add);
        } else if (expression instanceof ArrayInitializerExpression initializer) {
            operands.addAll(initializer.elements());
        } else if (expression instanceof ArrayAccessExpression access) {
            operands.add(access.array());
            operands.add(access.index());
        } else if (!(expression instanceof NameExpression
                || expression instanceof BooleanLiteralExpression || expression instanceof CharacterLiteralExpression
                || expression instanceof FloatingLiteralExpression || expression instanceof IntegerLiteralExpression
                || expression instanceof StringLiteralExpression || expression instanceof NullLiteralExpression
                || expression instanceof ThisExpression || expression instanceof SuperExpression
                || expression instanceof QualifiedThisExpression || expression instanceof InterfaceSuperExpression)) {
            return null;
        }
        return operands;
    }

    private Set<String> sequence(List<Expression> expressions, Set<String> held, Set<String> scope) {
        Set<String> state = held;
        for (Expression expression : expressions) {
            state = expression(expression, state, scope).held();
        }
        return state;
    }

    /** Meets {@code held} into every enclosing try region's states. */
    private void note(Set<String> held) {
        for (Region region : regions) {
            region.all = meet(region.all, held);
        }
    }

    /**
     * Records {@code held} as a state where lowering may add an exception edge (D309), in
     * every enclosing try region, and returns it.
     */
    private Set<String> thrown(Set<String> held) {
        note(held);
        for (Region region : regions) {
            region.thrown = meet(region.thrown, held);
        }
        return held;
    }

    /** Records a transfer's state, where pending deferred actions run and may throw. */
    private void transferred(Set<String> held) {
        if (pendingCleanups > 0) {
            thrown(held);
        }
    }

    /**
     * Whether lowering adds no exception edge for {@code expression} itself, beyond its
     * operands: literals other than strings, {@code this} and {@code super} forms, and a
     * logical negation, which only branches. Every other operator may throw, through a
     * call, a runtime check or unboxing.
     */
    private static boolean silent(Expression expression) {
        return expression instanceof BooleanLiteralExpression || expression instanceof CharacterLiteralExpression
                || expression instanceof FloatingLiteralExpression || expression instanceof IntegerLiteralExpression
                || expression instanceof NullLiteralExpression || expression instanceof ThisExpression
                || expression instanceof SuperExpression || expression instanceof QualifiedThisExpression
                || expression instanceof InterfaceSuperExpression
                || expression instanceof UnaryExpression unary && unary.operator() == UnaryOperator.NOT;
    }

    private static Set<String> meet(Set<String> first, Set<String> second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        Set<String> result = new HashSet<>(first);
        result.retainAll(second);
        return result;
    }

    private static Set<String> write(Set<String> held, String name, boolean holds) {
        return write(held, name, holds, false);
    }

    private static Set<String> write(Set<String> held, String name, boolean holds, boolean isNull) {
        if (held == null) {
            return null;
        }
        Set<String> result = new HashSet<>(held);
        result.remove(name);
        result.remove(NULL + name);
        if (holds) {
            result.add(name);
        }
        if (isNull) {
            result.add(NULL + name);
        }
        return result;
    }

    /** {@code held} less {@code names}; null names, unknown writes, keep nothing held. */
    private static Set<String> without(Set<String> held, Set<String> names) {
        if (held == null) {
            return null;
        }
        if (names == null) {
            return Set.of();
        }
        if (names.isEmpty()) {
            return held;
        }
        Set<String> result = new HashSet<>(held);
        for (String name : names) {
            result.remove(name);
            result.remove(NULL + name);
        }
        return result;
    }

    /**
     * The locals {@code statement} may write, by name, or null when it holds an expression
     * this analysis does not know; Java forbids reusing a local's name in its scope.
     */
    private static Set<String> writtenLocals(Statement statement) {
        Set<String> names = new HashSet<>();
        boolean known = Statements.visit(statement, expression -> {
            if (expression instanceof AssignmentExpression assignment
                    && assignment.target() instanceof NameExpression local) {
                names.add(local.name());
            } else if (expression instanceof UpdateExpression update
                    && update.target() instanceof NameExpression local) {
                names.add(local.name());
            } else if (expression instanceof InstanceOfExpression test) {
                test.binding().ifPresent(binding -> names.add(binding.name()));
            }
        }, nested -> {
            if (nested instanceof AssignmentStatement assignment
                    && assignment.target() instanceof NameExpression local) {
                names.add(local.name());
            } else if (nested instanceof LocalVariableDeclaration local) {
                names.add(local.name());
            }
        });
        return known ? names : null;
    }

    private static boolean writes(Statement statement, String name) {
        Set<String> names = writtenLocals(statement);
        return names == null || names.contains(name);
    }

    /** Visits every statement and expression a statement holds, outside class bodies. */
    private static final class Statements {
        private Statements() {
        }

        /** Visits {@code statement}; false when it holds an expression of an unknown kind. */
        static boolean visit(Statement statement, java.util.function.Consumer<Expression> expressions,
                             java.util.function.Consumer<Statement> statements) {
            statements.accept(statement);
            boolean known = true;
            for (Statement child : children(statement)) {
                known &= visit(child, expressions, statements);
            }
            for (Expression expression : expressionsOf(statement)) {
                known &= visit(expression, expressions, statements);
            }
            return known;
        }

        private static boolean visit(Expression expression, java.util.function.Consumer<Expression> expressions,
                                     java.util.function.Consumer<Statement> statements) {
            expressions.accept(expression);
            boolean known = true;
            if (expression instanceof SwitchExpression switched) {
                known &= visit(switched.selector(), expressions, statements);
                for (SwitchRule rule : switched.rules()) {
                    known &= visitRule(rule, expressions, statements);
                }
                for (SwitchGroup group : switched.groups()) {
                    for (Statement child : group.statements()) {
                        known &= visit(child, expressions, statements);
                    }
                }
                return known;
            }
            List<Expression> nested = subexpressions(expression);
            if (nested == null) {
                return false;
            }
            for (Expression child : nested) {
                known &= visit(child, expressions, statements);
            }
            return known;
        }

        private static boolean visitRule(SwitchRule rule, java.util.function.Consumer<Expression> expressions,
                                         java.util.function.Consumer<Statement> statements) {
            if (rule.body() instanceof SwitchRuleExpression result) {
                return visit(result.expression(), expressions, statements);
            }
            if (rule.body() instanceof SwitchRuleBlock block) {
                return visit(block.block(), expressions, statements);
            }
            if (rule.body() instanceof SwitchRuleThrow thrown) {
                return visit(thrown.statement(), expressions, statements);
            }
            return false;
        }

        private static List<Expression> subexpressions(Expression expression) {
            List<Expression> nested = new ArrayList<>();
            if (expression instanceof AssignmentExpression assignment) {
                nested.add(assignment.target());
                nested.add(assignment.value());
            } else if (expression instanceof ConditionalExpression conditional) {
                nested.add(conditional.condition());
                nested.add(conditional.whenTrue());
                nested.add(conditional.whenFalse());
            } else if (expression instanceof CastExpression cast) {
                nested.add(cast.operand());
            } else if (expression instanceof FieldAccessExpression access) {
                nested.add(access.receiver());
            } else if (expression instanceof InstanceOfExpression test) {
                nested.add(test.operand());
            } else if (expression instanceof UpdateExpression update) {
                nested.add(update.target());
            } else {
                List<Expression> operands = operands(expression);
                if (operands == null) return null;
                nested.addAll(operands);
            }
            return nested;
        }

        private static List<Statement> children(Statement statement) {
            List<Statement> children = new ArrayList<>();
            if (statement instanceof Block block) {
                children.addAll(block.statements());
            } else if (statement instanceof IfStatement conditional) {
                children.add(conditional.thenBranch());
                conditional.elseBranch().ifPresent(children::add);
            } else if (statement instanceof WhileStatement loop) {
                children.add(loop.body());
            } else if (statement instanceof DoWhileStatement loop) {
                children.add(loop.body());
            } else if (statement instanceof ForStatement loop) {
                loop.initializer().ifPresent(children::add);
                children.add(loop.body());
            } else if (statement instanceof EnhancedForStatement loop) {
                children.add(loop.body());
            } else if (statement instanceof LabeledStatement labeled) {
                children.add(labeled.body());
            } else if (statement instanceof SwitchStatement switched) {
                switched.groups().forEach(group -> children.addAll(group.statements()));
            } else if (statement instanceof ModernSwitchStatement switched) {
                for (SwitchRule rule : switched.rules()) {
                    if (rule.body() instanceof SwitchRuleBlock block) children.add(block.block());
                    if (rule.body() instanceof SwitchRuleThrow thrown) children.add(thrown.statement());
                }
            } else if (statement instanceof TryStatement guarded) {
                children.add(guarded.body());
                guarded.catches().forEach(caught -> children.add(caught.body()));
                guarded.finallyBlock().ifPresent(children::add);
            }
            return children;
        }

        private static List<Expression> expressionsOf(Statement statement) {
            List<Expression> expressions = new ArrayList<>();
            if (statement instanceof LocalVariableDeclaration local) {
                expressions.add(local.initializer());
            } else if (statement instanceof ExpressionStatement expression) {
                expressions.add(expression.expression());
            } else if (statement instanceof AssignmentStatement assignment) {
                expressions.add(assignment.target());
                expressions.add(assignment.value());
            } else if (statement instanceof FreeStatement free) {
                expressions.add(free.value());
            } else if (statement instanceof DeferStatement deferred) {
                expressions.add(deferred.call());
            } else if (statement instanceof IfStatement conditional) {
                expressions.add(conditional.condition());
            } else if (statement instanceof WhileStatement loop) {
                expressions.add(loop.condition());
            } else if (statement instanceof DoWhileStatement loop) {
                expressions.add(loop.condition());
            } else if (statement instanceof ForStatement loop) {
                loop.condition().ifPresent(expressions::add);
                expressions.addAll(loop.updates());
            } else if (statement instanceof EnhancedForStatement loop) {
                expressions.add(loop.iterable());
            } else if (statement instanceof SwitchStatement switched) {
                expressions.add(switched.selector());
            } else if (statement instanceof ModernSwitchStatement switched) {
                expressions.add(switched.selector());
                for (SwitchRule rule : switched.rules()) {
                    if (rule.body() instanceof SwitchRuleExpression result) expressions.add(result.expression());
                }
            } else if (statement instanceof ReturnStatement returned) {
                returned.value().ifPresent(expressions::add);
            } else if (statement instanceof ThrowStatement thrown) {
                expressions.add(thrown.value());
            } else if (statement instanceof YieldStatement yielded) {
                expressions.add(yielded.value());
            } else if (statement instanceof EnumConstantInitialization initialization) {
                expressions.addAll(initialization.constant().arguments());
            } else if (statement instanceof SuperConstructorInvocation invocation) {
                invocation.enclosingInstance().ifPresent(expressions::add);
                expressions.addAll(invocation.arguments());
            } else if (statement instanceof ThisConstructorInvocation invocation) {
                expressions.addAll(invocation.arguments());
            }
            return expressions;
        }
    }
}
