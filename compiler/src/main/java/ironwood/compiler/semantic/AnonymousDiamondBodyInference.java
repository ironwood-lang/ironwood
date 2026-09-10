// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AssignmentExpression;
import ironwood.compiler.ast.AssignmentOperator;
import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.SuperConstructorInvocation;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Infers an anonymous diamond's body view from its enclosing expression, without lowering it. */
final class AnonymousDiamondBodyInference {
    private final TypeSymbol owner;
    private final NewExpression allocation;
    private final InvocationPlanningContext context;
    private final InvocationPlanner planner;
    private final ClassHierarchy hierarchy;
    private Optional<IrType> inferred = Optional.empty();

    AnonymousDiamondBodyInference(TypeSymbol anonymous, InvocationPlanningContext context,
                                   ClassHierarchy hierarchy) {
        this.owner = anonymous.enclosingType().orElseThrow();
        this.allocation = anonymous.anonymousAllocation().orElseThrow();
        this.context = context;
        this.planner = new InvocationPlanner(context);
        this.hierarchy = hierarchy;
    }

    Optional<IrType> infer() {
        for (FieldSymbol field : owner.declaredFields().values()) {
            field.declaration().initializer().ifPresent(value -> plan(value, Optional.of(field.type())));
        }
        List<CallableSymbol> callables = new ArrayList<>(owner.constructors());
        callables.addAll(owner.declaredMethods().values());
        owner.destructor().ifPresent(callables::add);
        owner.staticInitializer().ifPresent(callables::add);
        for (CallableSymbol callable : callables) {
            callable.body().ifPresent(body -> visit(body, callable.returnType()));
        }
        if (owner.declaration() instanceof ClassDeclaration declaration) {
            declaration.instanceInitializations().stream().filter(Block.class::isInstance)
                    .map(Block.class::cast).forEach(block -> visit(block, IrType.VOID));
        }
        return inferred;
    }

    private void visit(Statement statement, IrType returnType) {
        if (inferred.isPresent() || !contains(statement.span())) {
            return;
        }
        if (statement instanceof Block block) {
            block.statements().forEach(child -> visit(child, returnType));
        } else if (statement instanceof LocalVariableDeclaration local) {
            plan(local.initializer(), resolve(local.type()));
        } else if (statement instanceof ReturnStatement returned) {
            returned.value().ifPresent(value -> plan(value, Optional.of(returnType)));
        } else if (statement instanceof AssignmentStatement assignment) {
            plan(new AssignmentExpression(assignment.target(), AssignmentOperator.ASSIGN,
                    assignment.equalsSpan(), assignment.value(), assignment.span()), Optional.empty());
        } else if (statement instanceof ExpressionStatement expression) {
            plan(expression.expression(), Optional.empty());
        } else if (statement instanceof ThrowStatement thrown) {
            plan(thrown.value(), Optional.empty());
        } else if (statement instanceof FreeStatement freed) {
            plan(freed.value(), Optional.empty());
        } else if (statement instanceof YieldStatement yielded) {
            plan(yielded.value(), Optional.empty());
        } else if (statement instanceof IfStatement conditional) {
            plan(conditional.condition(), Optional.of(IrType.I1));
            visit(conditional.thenBranch(), returnType);
            conditional.elseBranch().ifPresent(branch -> visit(branch, returnType));
        } else if (statement instanceof WhileStatement loop) {
            plan(loop.condition(), Optional.of(IrType.I1));
            visit(loop.body(), returnType);
        } else if (statement instanceof DoWhileStatement loop) {
            visit(loop.body(), returnType);
            plan(loop.condition(), Optional.of(IrType.I1));
        } else if (statement instanceof ForStatement loop) {
            loop.initializer().ifPresent(initializer -> visit(initializer, returnType));
            loop.condition().ifPresent(condition -> plan(condition, Optional.of(IrType.I1)));
            loop.updates().forEach(update -> plan(update, Optional.empty()));
            visit(loop.body(), returnType);
        } else if (statement instanceof EnhancedForStatement loop) {
            plan(loop.iterable(), Optional.empty());
            visit(loop.body(), returnType);
        } else if (statement instanceof LabeledStatement labeled) {
            visit(labeled.body(), returnType);
        } else if (statement instanceof TryStatement guarded) {
            visit(guarded.body(), returnType);
            guarded.catches().forEach(caught -> visit(caught.body(), returnType));
            guarded.finallyBlock().ifPresent(cleanup -> visit(cleanup, returnType));
        } else if (statement instanceof SwitchStatement switched) {
            plan(switched.selector(), Optional.empty());
            switched.groups().forEach(group -> group.statements()
                    .forEach(child -> visit(child, returnType)));
        } else if (statement instanceof ModernSwitchStatement switched) {
            plan(switched.selector(), Optional.empty());
            switched.rules().forEach(rule -> {
                if (rule.body() instanceof SwitchRuleBlock block) {
                    visit(block.block(), returnType);
                } else if (rule.body() instanceof SwitchRuleExpression expression) {
                    plan(expression.expression(), Optional.empty());
                }
            });
        } else if (statement instanceof ThisConstructorInvocation invocation) {
            planDelegation(owner.selfType(), invocation.arguments(), invocation.typeArguments(),
                    invocation.span());
        } else if (statement instanceof SuperConstructorInvocation invocation) {
            invocation.enclosingInstance().ifPresent(value -> plan(value, Optional.empty()));
            hierarchy.superclassType(owner.selfType()).ifPresent(parent ->
                    planDelegation(parent, invocation.arguments(), invocation.typeArguments(),
                            invocation.span()));
        }
    }

    private Optional<IrType> resolve(TypeName type) {
        InvocationPlanningResult<IrType> resolved = context.resolveType(type,
                InvocationPlanningContext.TypeUse.ORDINARY);
        return resolved.isResolved() ? Optional.of(resolved.resolvedValue()) : Optional.empty();
    }

    private void plan(Expression expression, Optional<IrType> expected) {
        if (inferred.isPresent() || !contains(expression.span())) {
            return;
        }
        InvocationPlanningResult<ExpressionTypePlan> result = planner.plan(expression, expected);
        if (result.isResolved()) {
            findAllocation(result.resolvedValue());
        }
    }

    private void planDelegation(IrType target, List<Expression> arguments,
                                 List<TypeName> typeArguments, SourceSpan span) {
        InvocationPlanningResult<InvocationPlan> result = planner.planConstructorDelegation(
                target, hierarchy.constructors(target), arguments, typeArguments, span);
        if (result.isResolved()) {
            result.resolvedValue().selected().arguments()
                    .forEach(argument -> findAllocation(argument.expressionPlan()));
        }
    }

    private void findAllocation(ExpressionTypePlan plan) {
        if (plan.expression() == allocation) {
            inferred = Optional.of(plan.type());
        } else {
            plan.operands().forEach(this::findAllocation);
        }
    }

    private boolean contains(SourceSpan span) {
        return span.start().offset() <= allocation.span().start().offset()
                && span.end().offset() >= allocation.span().end().offset();
    }
}
