// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AnonymousClassBody;
import ironwood.compiler.ast.ArrayAccessExpression;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.AssignmentExpression;
import ironwood.compiler.ast.AssignmentOperator;
import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.ConstructorDeclaration;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EmptyStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.EnumConstant;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.InstanceInitialization;
import ironwood.compiler.ast.InstanceOfExpression;
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.InterfaceMethodDeclaration;
import ironwood.compiler.ast.LocalClassDeclaration;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.Parameter;
import ironwood.compiler.ast.PatternFlow;
import ironwood.compiler.ast.QualifiedSuperConstructorExpression;
import ironwood.compiler.ast.QualifiedThisExpression;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.SuperConstructorInvocation;
import ironwood.compiler.ast.SwitchExpression;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchRuleThrow;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.TypeDeclaration;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UpdateExpression;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Computes declaration-identity-based capture and effectively-final facts. */
final class EffectivelyFinalCaptureAnalyzer {
    LocalClassSemantics.CaptureResult analyze(TypeDeclaration root) {
        return analyze(root, root.name());
    }

    LocalClassSemantics.CaptureResult analyze(TypeDeclaration root, String rootBinaryName) {
        LocalClassDiscovery.Result discovery =
                new LocalClassDiscovery().discover(root, rootBinaryName);
        return new Walker(discovery).analyze(root);
    }

    private static final class Walker {
        private final LocalClassDiscovery.Result discovery;
        private final List<LocalClassSemantics.VariableIdentity> variables = new ArrayList<>();
        private final List<LocalClassSemantics.Write> writes = new ArrayList<>();
        private final List<LocalClassSemantics.CaptureReference> references = new ArrayList<>();
        private final Map<String, PlanBuilder> plans = new LinkedHashMap<>();
        private final Deque<Map<String, LocalClassSemantics.VariableIdentity>> variableScopes =
                new ArrayDeque<>();
        private final Deque<ClassFrame> classFrames = new ArrayDeque<>();
        private final Map<String, LocalClassSemantics.ScopeDescriptor> scopesById =
                new LinkedHashMap<>();
        private final IdentityHashMap<InstanceOfExpression,
                LocalClassSemantics.VariableIdentity> patternVariables = new IdentityHashMap<>();
        private int nextVariableOrdinal;
        private boolean staticContext;
        private LocalClassSemantics.ScopeDescriptor currentScope;

        private Walker(LocalClassDiscovery.Result discovery) {
            this.discovery = discovery;
            discovery.classes().forEach(identity ->
                    plans.put(identity.binaryName(), new PlanBuilder(identity)));
            discovery.scopes().forEach(scope -> scopesById.put(scope.id(), scope));
        }

        private LocalClassSemantics.CaptureResult analyze(TypeDeclaration root) {
            LocalClassSemantics.ClassIdentity identity = discovery.classFor(root).orElseThrow();
            LocalClassSemantics.ScopeDescriptor scope = discovery.scopeFor(root).orElseThrow();
            walkType(root, identity, scope, Optional.empty());
            propagateRequirements();
            List<LocalClassSemantics.CaptureDiagnostic> diagnostics = diagnostics();
            LinkedHashMap<String, LocalClassSemantics.ClassCapturePlan> completed =
                    new LinkedHashMap<>();
            plans.forEach((name, plan) -> completed.put(name, plan.finish()));
            return new LocalClassSemantics.CaptureResult(discovery, variables, writes,
                    completed, diagnostics);
        }

        private void walkType(TypeDeclaration declaration,
                              LocalClassSemantics.ClassIdentity identity,
                              LocalClassSemantics.ScopeDescriptor scope,
                              Optional<String> directEnclosingInstance) {
            boolean previousStatic = staticContext;
            LocalClassSemantics.ScopeDescriptor previousScope = currentScope;
            ClassFrame frame = new ClassFrame(identity, fieldNames(declaration));
            classFrames.push(frame);
            currentScope = scope;
            // A declaration may occur in a static context, but its own class body has
            // fresh member contexts. The occurrence context is represented separately
            // by directEnclosingInstance.
            staticContext = false;
            PlanBuilder plan = plans.get(identity.binaryName());
            plan.directEnclosingInstance = directEnclosingInstance;
            if (declaration instanceof ClassDeclaration classDeclaration) {
                walkClassBody(classDeclaration);
            } else {
                walkInterfaceBody((InterfaceDeclaration) declaration);
            }
            classFrames.pop();
            currentScope = previousScope;
            staticContext = previousStatic;
        }

        private void walkClassBody(ClassDeclaration declaration) {
            List<BodyElement> elements = new ArrayList<>();
            declaration.enumConstants().forEach(constant ->
                    elements.add(new BodyElement(constant, 0, constant.span())));
            declaration.fields().stream().filter(FieldDeclaration::isStatic)
                    .forEach(field -> elements.add(new BodyElement(field, 1, field.span())));
            declaration.staticInitializations().stream().filter(Block.class::isInstance)
                    .map(Block.class::cast)
                    .forEach(block -> elements.add(new BodyElement(
                            new StaticInitializerBlock(block), 2, block.span())));
            declaration.instanceInitializations().forEach(initialization ->
                    elements.add(new BodyElement(initialization, 3, initialization.span())));
            declaration.constructors().forEach(constructor ->
                    elements.add(new BodyElement(constructor, 4, constructor.span())));
            declaration.methods().forEach(method ->
                    elements.add(new BodyElement(method, 5, method.span())));
            declaration.memberTypes().forEach(type ->
                    elements.add(new BodyElement(type, 6, type.span())));
            elements.stream().sorted(BodyElement.ORDER)
                    .forEach(element -> walkBodyElement(element.node()));
        }

        private void walkInterfaceBody(InterfaceDeclaration declaration) {
            List<BodyElement> elements = new ArrayList<>();
            declaration.fields().forEach(field ->
                    elements.add(new BodyElement(field, 0, field.span())));
            declaration.methods().forEach(method ->
                    elements.add(new BodyElement(method, 1, method.span())));
            declaration.memberTypes().forEach(type ->
                    elements.add(new BodyElement(type, 2, type.span())));
            elements.stream().sorted(BodyElement.ORDER)
                    .forEach(element -> walkBodyElement(element.node()));
        }

        private void walkAnonymousBody(AnonymousClassBody body) {
            List<BodyElement> elements = new ArrayList<>();
            body.fields().stream().filter(FieldDeclaration::isStatic)
                    .forEach(field -> elements.add(new BodyElement(field, 0, field.span())));
            body.instanceInitializations().forEach(initialization ->
                    elements.add(new BodyElement(initialization, 1, initialization.span())));
            body.methods().forEach(method ->
                    elements.add(new BodyElement(method, 2, method.span())));
            body.memberTypes().forEach(type ->
                    elements.add(new BodyElement(type, 3, type.span())));
            elements.stream().sorted(BodyElement.ORDER)
                    .forEach(element -> walkBodyElement(element.node()));
        }

        private void walkBodyElement(Object node) {
            if (node instanceof EnumConstant constant) {
                walkEnumConstant(constant);
            } else if (node instanceof FieldDeclaration field) {
                field.initializer().ifPresent(initializer -> withContext(field, field.isStatic(),
                        () -> walkExpression(initializer)));
            } else if (node instanceof StaticInitializerBlock staticInitializer) {
                withContext(staticInitializer.block(), true,
                        () -> walkBlock(staticInitializer.block()));
            } else if (node instanceof Block initializer) {
                withContext(initializer, false, () -> walkBlock(initializer));
            } else if (node instanceof ConstructorDeclaration constructor) {
                withCallable(constructor, false, constructor.parameters(), () -> {
                    constructor.superInvocation().ifPresent(this::walkSuperInvocation);
                    constructor.thisInvocation().ifPresent(invocation ->
                            invocation.arguments().forEach(this::walkExpression));
                    walkBlock(constructor.body());
                });
            } else if (node instanceof ironwood.compiler.ast.MethodDeclaration method) {
                withCallable(method, method.isStatic(), method.parameters(), () ->
                        method.body().ifPresent(this::walkBlock));
            } else if (node instanceof InterfaceMethodDeclaration method) {
                withCallable(method, method.isStatic(), method.parameters(), () ->
                        method.body().ifPresent(this::walkBlock));
            } else {
                TypeDeclaration member = (TypeDeclaration) node;
                LocalClassSemantics.ClassIdentity identity = discovery.classFor(member).orElseThrow();
                LocalClassSemantics.ScopeDescriptor scope = discovery.scopeFor(member).orElse(currentScope);
                Optional<String> enclosing = member.isStatic()
                        ? Optional.empty() : Optional.of(currentClass().binaryName());
                walkType(member, identity, scope, enclosing);
            }
        }

        private void walkEnumConstant(EnumConstant constant) {
            withContext(constant, true,
                    () -> constant.arguments().forEach(this::walkExpression));
            if (constant.classBody().isEmpty()) {
                return;
            }
            LocalClassSemantics.ClassIdentity identity = discovery.classFor(constant).orElseThrow();
            plans.get(currentClass().binaryName()).constructedLexicalTypes.add(identity.binaryName());
            AnonymousClassBody body = constant.classBody().orElseThrow();
            LocalClassSemantics.ScopeDescriptor scope = discovery.scopeFor(body)
                    .orElse(currentScope);
            boolean previousStatic = staticContext;
            LocalClassSemantics.ScopeDescriptor previousScope = currentScope;
            classFrames.push(new ClassFrame(identity, fieldNames(body)));
            staticContext = false;
            currentScope = scope;
            plans.get(identity.binaryName()).directEnclosingInstance = Optional.empty();
            walkAnonymousBody(body);
            classFrames.pop();
            currentScope = previousScope;
            staticContext = previousStatic;
        }

        private void withCallable(Object node, boolean isStatic, List<Parameter> parameters,
                                  Runnable body) {
            withContext(node, isStatic, () -> {
                pushVariables();
                parameters.forEach(parameter -> declare(parameter.name(),
                        LocalClassSemantics.VariableKind.PARAMETER, parameter.type(),
                        parameter.nameSpan()));
                body.run();
                popVariables();
            });
        }

        private void withContext(Object node, boolean isStatic, Runnable action) {
            boolean previousStatic = staticContext;
            LocalClassSemantics.ScopeDescriptor previousScope = currentScope;
            staticContext = isStatic;
            currentScope = discovery.scopeFor(node).orElse(currentScope);
            action.run();
            currentScope = previousScope;
            staticContext = previousStatic;
        }

        private void walkBlock(Block block) {
            LocalClassSemantics.ScopeDescriptor previousScope = currentScope;
            currentScope = discovery.scopeFor(block).orElse(currentScope);
            pushVariables();
            block.statements().forEach(this::walkStatement);
            popVariables();
            currentScope = previousScope;
        }

        private void walkStatement(Statement statement) {
            if (statement instanceof Block block) {
                walkBlock(block);
            } else if (statement instanceof LocalClassDeclaration local) {
                LocalClassSemantics.ClassIdentity identity = discovery.classFor(local).orElseThrow();
                LocalClassSemantics.ScopeDescriptor scope = discovery.scopeFor(
                        local.declaration()).orElse(currentScope);
                Optional<String> enclosing = staticContext ? Optional.empty()
                        : Optional.of(currentClass().binaryName());
                walkType(local.declaration(), identity, scope, enclosing);
            } else if (statement instanceof LocalVariableDeclaration local) {
                walkExpression(local.initializer());
                declare(local.name(), LocalClassSemantics.VariableKind.LOCAL,
                        local.type(), local.nameSpan());
            } else if (statement instanceof AssignmentStatement assignment) {
                walkAssignmentTarget(assignment.target(),
                        LocalClassSemantics.WriteKind.SIMPLE_ASSIGNMENT, false);
                walkExpression(assignment.value());
            } else if (statement instanceof ExpressionStatement expression) {
                walkExpression(expression.expression());
            } else if (statement instanceof FreeStatement free) {
                walkExpression(free.value());
            } else if (statement instanceof ReturnStatement returned) {
                returned.value().ifPresent(this::walkExpression);
            } else if (statement instanceof ThrowStatement thrown) {
                walkExpression(thrown.value());
            } else if (statement instanceof YieldStatement yielded) {
                walkExpression(yielded.value());
            } else if (statement instanceof SuperConstructorInvocation invocation) {
                walkSuperInvocation(invocation);
            } else if (statement instanceof ThisConstructorInvocation invocation) {
                invocation.arguments().forEach(this::walkExpression);
            } else if (statement instanceof IfStatement conditional) {
                walkExpression(conditional.condition());
                PatternFlow.Result flow = PatternFlow.analyze(conditional.condition());
                walkScoped(conditional.thenBranch(), flow.whenTrue());
                conditional.elseBranch().ifPresent(branch ->
                        walkScoped(branch, flow.whenFalse()));
                boolean thenCompletes = PatternFlow.canCompleteNormally(conditional.thenBranch());
                boolean elseCompletes = conditional.elseBranch()
                        .map(PatternFlow::canCompleteNormally)
                        .orElse(true);
                if (!thenCompletes && elseCompletes) {
                    activatePatternVariables(flow.whenFalse());
                } else if (thenCompletes && !elseCompletes) {
                    activatePatternVariables(flow.whenTrue());
                }
            } else if (statement instanceof WhileStatement loop) {
                walkExpression(loop.condition());
                PatternFlow.Result flow = PatternFlow.analyze(loop.condition());
                walkScoped(loop.body(), flow.whenTrue());
                if (!PatternFlow.containsBreakForCurrentLoop(loop.body())) {
                    activatePatternVariables(flow.whenFalse());
                }
            } else if (statement instanceof DoWhileStatement loop) {
                walkScoped(loop.body());
                walkExpression(loop.condition());
                PatternFlow.Result flow = PatternFlow.analyze(loop.condition());
                if (!PatternFlow.containsBreakForCurrentLoop(loop.body())) {
                    activatePatternVariables(flow.whenFalse());
                }
            } else if (statement instanceof ForStatement loop) {
                pushVariables();
                loop.initializer().ifPresent(this::walkStatement);
                loop.condition().ifPresent(this::walkExpression);
                PatternFlow.Result flow = loop.condition().map(PatternFlow::analyze)
                        .orElseGet(() -> PatternFlow.analyze(
                                new ironwood.compiler.ast.BooleanLiteralExpression(
                                        true, loop.span())));
                walkScoped(loop.body(), flow.whenTrue());
                pushVariables();
                activatePatternVariables(flow.whenTrue());
                loop.updates().forEach(this::walkExpression);
                popVariables();
                popVariables();
                if (!PatternFlow.containsBreakForCurrentLoop(loop.body())) {
                    activatePatternVariables(flow.whenFalse());
                }
            } else if (statement instanceof EnhancedForStatement loop) {
                walkExpression(loop.iterable());
                LocalClassSemantics.ScopeDescriptor previousScope = currentScope;
                currentScope = discovery.scopeFor(loop).orElse(currentScope);
                pushVariables();
                declare(loop.variableName(), LocalClassSemantics.VariableKind.LOCAL,
                        loop.variableType(), loop.variableNameSpan());
                walkStatement(loop.body());
                popVariables();
                currentScope = previousScope;
            } else if (statement instanceof LabeledStatement labeled) {
                walkScoped(labeled.body());
            } else if (statement instanceof EmptyStatement) {
                // No capture-relevant state.
            } else if (statement instanceof SwitchStatement switched) {
                walkExpression(switched.selector());
                switched.groups().forEach(group -> group.labels().forEach(label ->
                        label.value().ifPresent(this::walkExpression)));
                LocalClassSemantics.ScopeDescriptor previousScope = currentScope;
                currentScope = discovery.scopeFor(switched).orElse(currentScope);
                pushVariables();
                switched.groups().forEach(group ->
                        group.statements().forEach(this::walkStatement));
                popVariables();
                currentScope = previousScope;
            } else if (statement instanceof ModernSwitchStatement switched) {
                walkExpression(switched.selector());
                LocalClassSemantics.ScopeDescriptor previousScope = currentScope;
                currentScope = discovery.scopeFor(switched).orElse(currentScope);
                pushVariables();
                walkSwitchRules(switched.rules());
                popVariables();
                currentScope = previousScope;
            } else if (statement instanceof TryStatement guarded) {
                walkBlock(guarded.body());
                guarded.catches().forEach(caught -> {
                    pushVariables();
                    declare(caught.variableName(),
                            LocalClassSemantics.VariableKind.CATCH_PARAMETER,
                            caught.types(), caught.variableNameSpan());
                    walkBlock(caught.body());
                    popVariables();
                });
                guarded.finallyBlock().ifPresent(this::walkBlock);
            }
        }

        private void walkScoped(Statement statement) {
            walkScoped(statement, List.of());
        }

        private void walkScoped(Statement statement, List<PatternFlow.Binding> bindings) {
            LocalClassSemantics.ScopeDescriptor previousScope = currentScope;
            currentScope = discovery.scopeFor(statement).orElse(currentScope);
            pushVariables();
            activatePatternVariables(bindings);
            walkStatement(statement);
            popVariables();
            currentScope = previousScope;
        }

        private void walkExpression(Expression expression) {
            if (expression instanceof SwitchExpression switched) {
                walkExpression(switched.selector());
                LocalClassSemantics.ScopeDescriptor previousScope = currentScope;
                currentScope = discovery.scopeFor(switched).orElse(currentScope);
                pushVariables();
                if (switched.arrowRules()) {
                    walkSwitchRules(switched.rules());
                } else {
                    switched.groups().forEach(group -> {
                        group.labels().forEach(label ->
                                label.value().ifPresent(this::walkExpression));
                        group.statements().forEach(this::walkStatement);
                    });
                }
                popVariables();
                currentScope = previousScope;
            } else if (expression instanceof NameExpression name) {
                reference(name, false);
            } else if (expression instanceof NewExpression allocation) {
                PlanBuilder constructingPlan = plans.get(currentClass().binaryName());
                String namedTarget = resolveConstructedLexicalType(allocation);
                allocation.enclosingInstance().ifPresent(this::walkExpression);
                allocation.arguments().forEach(this::walkExpression);
                allocation.anonymousClassBody().ifPresent(body -> {
                    LocalClassSemantics.ClassIdentity identity =
                            discovery.classFor(allocation).orElseThrow();
                    constructingPlan.constructedLexicalTypes.add(identity.binaryName());
                    if (namedTarget != null) {
                        plans.get(identity.binaryName()).constructedLexicalTypes.add(namedTarget);
                    }
                    LocalClassSemantics.ScopeDescriptor scope =
                            discovery.scopeFor(body).orElse(currentScope);
                    Optional<String> enclosing = staticContext ? Optional.empty()
                            : Optional.of(currentClass().binaryName());
                    boolean previousStatic = staticContext;
                    LocalClassSemantics.ScopeDescriptor previousScope = currentScope;
                    classFrames.push(new ClassFrame(identity, fieldNames(body)));
                    staticContext = false;
                    currentScope = scope;
                    plans.get(identity.binaryName()).directEnclosingInstance = enclosing;
                    walkAnonymousBody(body);
                    classFrames.pop();
                    currentScope = previousScope;
                    staticContext = previousStatic;
                });
                if (allocation.anonymousClassBody().isEmpty() && namedTarget != null) {
                    constructingPlan.constructedLexicalTypes.add(namedTarget);
                }
            } else if (expression instanceof ArrayCreationExpression creation) {
                creation.length().ifPresent(this::walkExpression);
                creation.initializer().ifPresent(this::walkExpression);
            } else if (expression instanceof ArrayInitializerExpression initializer) {
                initializer.elements().forEach(this::walkExpression);
            } else if (expression instanceof ArrayAccessExpression access) {
                walkExpression(access.array());
                walkExpression(access.index());
            } else if (expression instanceof AssignmentExpression assignment) {
                LocalClassSemantics.WriteKind kind = assignment.operator() == AssignmentOperator.ASSIGN
                        ? LocalClassSemantics.WriteKind.SIMPLE_ASSIGNMENT
                        : LocalClassSemantics.WriteKind.COMPOUND_ASSIGNMENT;
                walkAssignmentTarget(assignment.target(), kind,
                        assignment.operator() != AssignmentOperator.ASSIGN);
                walkExpression(assignment.value());
            } else if (expression instanceof BinaryExpression binary) {
                walkExpression(binary.left());
                if (binary.operator() == ironwood.compiler.ast.BinaryOperator.LOGICAL_AND
                        || binary.operator() == ironwood.compiler.ast.BinaryOperator.LOGICAL_OR) {
                    pushVariables();
                    activatePatternVariables(PatternFlow.bindingsForRightOperand(binary));
                    walkExpression(binary.right());
                    popVariables();
                } else {
                    walkExpression(binary.right());
                }
            } else if (expression instanceof CallExpression call) {
                call.receiver().ifPresent(this::walkExpression);
                if (call.receiver().isEmpty()) {
                    plans.get(currentClass().binaryName()).unresolvedNames
                            .add(call.methodName() + "()");
                }
                call.arguments().forEach(this::walkExpression);
            } else if (expression instanceof CastExpression cast) {
                walkExpression(cast.operand());
            } else if (expression instanceof ConditionalExpression conditional) {
                walkExpression(conditional.condition());
                PatternFlow.Result flow = PatternFlow.analyze(conditional.condition());
                pushVariables();
                activatePatternVariables(flow.whenTrue());
                walkExpression(conditional.whenTrue());
                popVariables();
                pushVariables();
                activatePatternVariables(flow.whenFalse());
                walkExpression(conditional.whenFalse());
                popVariables();
            } else if (expression instanceof FieldAccessExpression access) {
                walkExpression(access.receiver());
            } else if (expression instanceof InstanceOfExpression typeTest) {
                walkExpression(typeTest.operand());
                typeTest.binding().ifPresent(binding -> patternVariables.put(typeTest,
                        createVariable(binding.name(), LocalClassSemantics.VariableKind.PATTERN,
                                List.of(typeTest.targetType()), binding.nameSpan())));
            } else if (expression instanceof QualifiedSuperConstructorExpression invocation) {
                walkExpression(invocation.enclosingInstance());
                invocation.arguments().forEach(this::walkExpression);
            } else if (expression instanceof QualifiedThisExpression qualifiedThis) {
                requireQualifiedEnclosingInstance(qualifiedThis);
            } else if (expression instanceof UnaryExpression unary) {
                walkExpression(unary.operand());
            } else if (expression instanceof UpdateExpression update) {
                walkAssignmentTarget(update.target(), LocalClassSemantics.WriteKind.UPDATE, true);
            }
        }

        private void walkSwitchRules(List<ironwood.compiler.ast.SwitchRule> rules) {
            rules.forEach(rule -> {
                rule.labels().forEach(label ->
                        label.value().ifPresent(this::walkExpression));
                if (rule.body() instanceof SwitchRuleExpression result) {
                    walkExpression(result.expression());
                } else if (rule.body() instanceof SwitchRuleBlock block) {
                    walkBlock(block.block());
                } else if (rule.body() instanceof SwitchRuleThrow thrown) {
                    walkStatement(thrown.statement());
                }
            });
        }

        private String resolveConstructedLexicalType(NewExpression allocation) {
            String sourceName = allocation.className();
            if (sourceName.indexOf('.') >= 0 || allocation.enclosingInstance().isPresent()) {
                return null;
            }
            LocalClassSemantics.ClassIdentity selected = null;
            int selectedDepth = -1;
            for (LocalClassSemantics.ClassIdentity candidate : discovery.classes()) {
                if (candidate.kind() != LocalClassSemantics.ClassKind.LOCAL
                        || candidate.simpleName().filter(sourceName::equals).isEmpty()
                        || candidate.span().start().offset() > allocation.span().start().offset()) {
                    continue;
                }
                Object node = discovery.nodeFor(candidate.binaryName()).orElse(null);
                if (!(node instanceof LocalClassDeclaration local)) {
                    continue;
                }
                LocalClassSemantics.ScopeDescriptor typeScope = discovery
                        .scopeFor(local.declaration()).orElse(null);
                if (typeScope == null || typeScope.parentId().isEmpty()) {
                    continue;
                }
                int depth = scopeDistance(typeScope.parentId().orElseThrow(), currentScope.id());
                if (depth >= 0 && (selected == null || depth < selectedDepth
                        || depth == selectedDepth && candidate.span().start().offset()
                        > selected.span().start().offset())) {
                    selected = candidate;
                    selectedDepth = depth;
                }
            }
            if (selected != null) {
                return selected.binaryName();
            }
            for (String owner = currentClass().binaryName(); owner != null; ) {
                for (LocalClassSemantics.ClassIdentity candidate : discovery.classes()) {
                    if (candidate.kind() == LocalClassSemantics.ClassKind.MEMBER
                            && candidate.simpleName().filter(sourceName::equals).isPresent()
                            && candidate.enclosingBinaryName().filter(owner::equals).isPresent()) {
                        return candidate.binaryName();
                    }
                }
                owner = discovery.classNamed(owner)
                        .flatMap(LocalClassSemantics.ClassIdentity::enclosingBinaryName)
                        .orElse(null);
            }
            return null;
        }

        private int scopeDistance(String ancestorId, String descendantId) {
            int distance = 0;
            for (String scopeId = descendantId; scopeId != null; distance++) {
                if (scopeId.equals(ancestorId)) {
                    return distance;
                }
                LocalClassSemantics.ScopeDescriptor scope = scopesById.get(scopeId);
                scopeId = scope == null ? null : scope.parentId().orElse(null);
            }
            return -1;
        }

        private void walkAssignmentTarget(Expression target, LocalClassSemantics.WriteKind kind,
                                          boolean readBeforeWrite) {
            if (target instanceof NameExpression name) {
                LocalClassSemantics.VariableIdentity variable = resolve(name.name());
                if (variable == null) {
                    plans.get(currentClass().binaryName()).unresolvedNames.add(name.name());
                    return;
                }
                capture(variable, name.span(), true);
                writes.add(new LocalClassSemantics.Write(variable, kind, name.span(),
                        currentClass().binaryName()));
                if (readBeforeWrite) {
                    capture(variable, name.span(), false);
                }
            } else {
                walkExpression(target);
            }
        }

        private void walkSuperInvocation(SuperConstructorInvocation invocation) {
            invocation.enclosingInstance().ifPresent(this::walkExpression);
            invocation.arguments().forEach(this::walkExpression);
        }

        private void reference(NameExpression name, boolean assignmentTarget) {
            LocalClassSemantics.VariableIdentity variable = resolve(name.name());
            if (variable == null) {
                plans.get(currentClass().binaryName()).unresolvedNames.add(name.name());
                return;
            }
            capture(variable, name.span(), assignmentTarget);
        }

        private void capture(LocalClassSemantics.VariableIdentity variable, SourceSpan span,
                             boolean assignmentTarget) {
            if (variable.ownerBinaryName().equals(currentClass().binaryName())) {
                return;
            }
            LocalClassSemantics.CaptureReference reference =
                    new LocalClassSemantics.CaptureReference(variable,
                            currentClass().binaryName(), span, staticContext, assignmentTarget);
            references.add(reference);
            PlanBuilder plan = plans.get(currentClass().binaryName());
            plan.directCaptures.add(variable);
            plan.references.add(reference);
        }

        private void requireQualifiedEnclosingInstance(QualifiedThisExpression expression) {
            String target = null;
            for (ClassFrame frame : classFrames) {
                if (frame.identity.binaryName().equals(expression.typeName())
                        || frame.identity.simpleName().orElse("").equals(expression.typeName())) {
                    target = frame.identity.binaryName();
                    break;
                }
            }
            if (target == null || target.equals(currentClass().binaryName())) {
                return;
            }
            PlanBuilder plan = plans.get(currentClass().binaryName());
            plan.requiredEnclosingInstances.add(target);
            if (staticContext) {
                plan.staticEnclosingReferences.add(expression.span());
            }
        }

        private void declare(String name, LocalClassSemantics.VariableKind kind,
                             ironwood.compiler.ast.TypeName type, SourceSpan nameSpan) {
            declare(name, kind, List.of(type), nameSpan);
        }

        private void declare(String name, LocalClassSemantics.VariableKind kind,
                             List<ironwood.compiler.ast.TypeName> types, SourceSpan nameSpan) {
            if (variableScopes.isEmpty()) {
                pushVariables();
            }
            LocalClassSemantics.VariableIdentity identity =
                    createVariable(name, kind, types, nameSpan);
            variableScopes.peek().put(name, identity);
        }

        private LocalClassSemantics.VariableIdentity createVariable(
                String name, LocalClassSemantics.VariableKind kind,
                List<ironwood.compiler.ast.TypeName> types, SourceSpan nameSpan) {
            String id = currentClass().binaryName() + "#var" + nextVariableOrdinal
                    + ":" + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + ":" + name + "@" + nameSpan.start().offset();
            LocalClassSemantics.VariableIdentity identity =
                    new LocalClassSemantics.VariableIdentity(id, name, kind,
                            currentClass().binaryName(), currentScope.id(), types,
                            nameSpan, nextVariableOrdinal++);
            variables.add(identity);
            return identity;
        }

        private void activatePatternVariables(List<PatternFlow.Binding> bindings) {
            if (variableScopes.isEmpty()) {
                pushVariables();
            }
            for (PatternFlow.Binding binding : bindings) {
                LocalClassSemantics.VariableIdentity identity =
                        patternVariables.get(binding.expression());
                if (identity != null) {
                    variableScopes.peek().put(binding.variable().name(), identity);
                }
            }
        }

        private LocalClassSemantics.VariableIdentity resolve(String name) {
            for (Map<String, LocalClassSemantics.VariableIdentity> scope : variableScopes) {
                LocalClassSemantics.VariableIdentity candidate = scope.get(name);
                if (candidate == null) {
                    continue;
                }
                if (!candidate.ownerBinaryName().equals(currentClass().binaryName())
                        && fieldShadows(name, candidate.ownerBinaryName())) {
                    return null;
                }
                return candidate;
            }
            return null;
        }

        private boolean fieldShadows(String name, String declarationOwner) {
            for (ClassFrame frame : classFrames) {
                if (frame.identity.binaryName().equals(declarationOwner)) {
                    return false;
                }
                if (frame.fieldNames.contains(name)) {
                    return true;
                }
            }
            return false;
        }

        private void propagateRequirements() {
            for (PlanBuilder plan : plans.values()) {
                plan.requiredCaptures.addAll(plan.directCaptures);
                plan.directEnclosingInstance.ifPresent(plan.requiredEnclosingInstances::add);
                for (LocalClassSemantics.VariableIdentity capture : plan.directCaptures) {
                    String current = plan.identity.binaryName();
                    while (!current.equals(capture.ownerBinaryName())) {
                        LocalClassSemantics.ClassIdentity identity = discovery.classNamed(current)
                                .orElse(null);
                        if (identity == null || identity.enclosingBinaryName().isEmpty()) {
                            break;
                        }
                        current = identity.enclosingBinaryName().orElseThrow();
                        if (!current.equals(capture.ownerBinaryName())) {
                            PlanBuilder enclosing = plans.get(current);
                            if (enclosing != null) {
                                enclosing.requiredCaptures.add(capture);
                            }
                        }
                    }
                }
            }
            boolean changed;
            do {
                changed = false;
                for (PlanBuilder plan : plans.values()) {
                    Set<String> additions = new LinkedHashSet<>();
                    for (String required : plan.requiredEnclosingInstances) {
                        PlanBuilder requiredPlan = plans.get(required);
                        if (requiredPlan != null) {
                            additions.addAll(requiredPlan.requiredEnclosingInstances);
                        }
                    }
                    if (plan.requiredEnclosingInstances.addAll(additions)) {
                        changed = true;
                    }
                    for (String constructed : plan.constructedLexicalTypes) {
                        PlanBuilder dependency = plans.get(constructed);
                        if (dependency == null) {
                            continue;
                        }
                        for (LocalClassSemantics.VariableIdentity capture
                                : dependency.requiredCaptures) {
                            if (!capture.ownerBinaryName().equals(plan.identity.binaryName())
                                    && plan.requiredCaptures.add(capture)) {
                                changed = true;
                            }
                        }
                    }
                }
            } while (changed);
        }

        private List<LocalClassSemantics.CaptureDiagnostic> diagnostics() {
            Set<LocalClassSemantics.VariableIdentity> written = new LinkedHashSet<>();
            writes.forEach(write -> written.add(write.variable()));
            List<LocalClassSemantics.CaptureDiagnostic> result = new ArrayList<>();
            Set<String> emitted = new LinkedHashSet<>();
            for (LocalClassSemantics.CaptureReference reference : references) {
                if (written.contains(reference.variable())) {
                    addDiagnostic(result, emitted,
                            LocalClassSemantics.DiagnosticCode.NON_EFFECTIVELY_FINAL_CAPTURE,
                            "captured variable '" + reference.variable().name()
                                    + "' is not final or effectively final",
                            reference.span(), reference.capturingClassBinaryName(),
                            Optional.of(reference.variable()));
                }
                if (reference.assignmentTarget()) {
                    addDiagnostic(result, emitted,
                            LocalClassSemantics.DiagnosticCode.WRITE_TO_CAPTURED_VARIABLE,
                            "captured variable '" + reference.variable().name()
                                    + "' cannot be assigned from a nested class",
                            reference.span(), reference.capturingClassBinaryName(),
                            Optional.of(reference.variable()));
                }
                if (reference.fromStaticContext()) {
                    addDiagnostic(result, emitted,
                            LocalClassSemantics.DiagnosticCode.CAPTURE_FROM_STATIC_CONTEXT,
                            "variable '" + reference.variable().name()
                                    + "' cannot be captured from a static context",
                            reference.span(), reference.capturingClassBinaryName(),
                            Optional.of(reference.variable()));
                }
            }
            for (PlanBuilder plan : plans.values()) {
                for (SourceSpan span : plan.staticEnclosingReferences) {
                    addDiagnostic(result, emitted,
                            LocalClassSemantics.DiagnosticCode.CAPTURE_FROM_STATIC_CONTEXT,
                            "an enclosing instance cannot be referenced from a static context",
                            span, plan.identity.binaryName(), Optional.empty());
                }
            }
            return List.copyOf(result);
        }

        private static void addDiagnostic(
                List<LocalClassSemantics.CaptureDiagnostic> result, Set<String> emitted,
                LocalClassSemantics.DiagnosticCode code, String message, SourceSpan span,
                String className, Optional<LocalClassSemantics.VariableIdentity> variable) {
            String key = code + ":" + className + ":" + span.start().offset() + ":"
                    + variable.map(LocalClassSemantics.VariableIdentity::id).orElse("");
            if (emitted.add(key)) {
                result.add(new LocalClassSemantics.CaptureDiagnostic(
                        code, message, span, className, variable));
            }
        }

        private void pushVariables() {
            variableScopes.push(new LinkedHashMap<>());
        }

        private void popVariables() {
            variableScopes.pop();
        }

        private LocalClassSemantics.ClassIdentity currentClass() {
            return classFrames.peek().identity;
        }

        private static Set<String> fieldNames(TypeDeclaration declaration) {
            if (declaration instanceof ClassDeclaration classDeclaration) {
                return classDeclaration.fields().stream().map(FieldDeclaration::name)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            }
            return ((InterfaceDeclaration) declaration).fields().stream()
                    .map(FieldDeclaration::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        private static Set<String> fieldNames(AnonymousClassBody body) {
            return body.fields().stream().map(FieldDeclaration::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private static final class PlanBuilder {
        private final LocalClassSemantics.ClassIdentity identity;
        private final Set<LocalClassSemantics.VariableIdentity> directCaptures =
                new LinkedHashSet<>();
        private final Set<LocalClassSemantics.VariableIdentity> requiredCaptures =
                new LinkedHashSet<>();
        private final Set<String> constructedLexicalTypes = new LinkedHashSet<>();
        private Optional<String> directEnclosingInstance = Optional.empty();
        private final Set<String> requiredEnclosingInstances = new LinkedHashSet<>();
        private final List<LocalClassSemantics.CaptureReference> references = new ArrayList<>();
        private final Set<String> unresolvedNames = new LinkedHashSet<>();
        private final List<SourceSpan> staticEnclosingReferences = new ArrayList<>();

        private PlanBuilder(LocalClassSemantics.ClassIdentity identity) {
            this.identity = identity;
        }

        private LocalClassSemantics.ClassCapturePlan finish() {
            return new LocalClassSemantics.ClassCapturePlan(identity, directCaptures,
                    requiredCaptures, constructedLexicalTypes, directEnclosingInstance,
                    requiredEnclosingInstances,
                    references, unresolvedNames);
        }
    }

    private record ClassFrame(LocalClassSemantics.ClassIdentity identity,
                              Set<String> fieldNames) {
    }

    private record BodyElement(Object node, int kindOrder, SourceSpan span) {
        private static final Comparator<BodyElement> ORDER = Comparator
                .comparingInt((BodyElement element) -> element.span.start().offset())
                .thenComparingInt(BodyElement::kindOrder)
                .thenComparingInt(element -> element.span.end().offset());
    }

    private record StaticInitializerBlock(Block block) {
    }
}
