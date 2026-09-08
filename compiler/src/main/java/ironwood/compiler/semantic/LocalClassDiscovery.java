// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AnonymousClassBody;
import ironwood.compiler.ast.ArrayAccessExpression;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.AssignmentExpression;
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
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.QualifiedSuperConstructorExpression;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Assigns stable Java-shaped identities to lexically declared classes. */
final class LocalClassDiscovery {
    Result discover(TypeDeclaration root) {
        return discover(root, root.name());
    }

    Result discover(TypeDeclaration root, String rootBinaryName) {
        if (root == null || rootBinaryName == null || rootBinaryName.isBlank()) {
            throw new IllegalArgumentException("lexical discovery requires a root type and binary name");
        }
        Builder builder = new Builder(rootBinaryName);
        builder.discoverRoot(root);
        return builder.finish();
    }

    static final class Result {
        private final String rootBinaryName;
        private final List<LocalClassSemantics.ClassIdentity> classes;
        private final List<LocalClassSemantics.ScopeDescriptor> scopes;
        private final Map<String, LocalClassSemantics.ClassIdentity> classesByName;
        private final Map<String, Object> nodesByClassName;
        private final IdentityHashMap<Object, LocalClassSemantics.ClassIdentity> classesByNode;
        private final IdentityHashMap<Object, LocalClassSemantics.ScopeDescriptor> scopesByNode;

        private Result(String rootBinaryName,
                       List<LocalClassSemantics.ClassIdentity> classes,
                       List<LocalClassSemantics.ScopeDescriptor> scopes,
                       IdentityHashMap<Object, LocalClassSemantics.ClassIdentity> classesByNode,
                       Map<String, Object> nodesByClassName,
                       IdentityHashMap<Object, LocalClassSemantics.ScopeDescriptor> scopesByNode) {
            this.rootBinaryName = rootBinaryName;
            this.classes = List.copyOf(classes);
            this.scopes = List.copyOf(scopes);
            LinkedHashMap<String, LocalClassSemantics.ClassIdentity> names = new LinkedHashMap<>();
            classes.forEach(identity -> names.put(identity.binaryName(), identity));
            classesByName = Collections.unmodifiableMap(names);
            this.nodesByClassName = Collections.unmodifiableMap(
                    new LinkedHashMap<>(nodesByClassName));
            this.classesByNode = new IdentityHashMap<>(classesByNode);
            this.scopesByNode = new IdentityHashMap<>(scopesByNode);
        }

        String rootBinaryName() {
            return rootBinaryName;
        }

        List<LocalClassSemantics.ClassIdentity> classes() {
            return classes;
        }

        List<LocalClassSemantics.ClassIdentity> lexicalClasses() {
            return classes.stream().filter(LocalClassSemantics.ClassIdentity::isLexicalClass).toList();
        }

        List<LocalClassSemantics.ScopeDescriptor> scopes() {
            return scopes;
        }

        Optional<LocalClassSemantics.ClassIdentity> classNamed(String binaryName) {
            return Optional.ofNullable(classesByName.get(binaryName));
        }

        Optional<LocalClassSemantics.ClassIdentity> classFor(Object astNode) {
            return Optional.ofNullable(classesByNode.get(astNode));
        }

        Optional<Object> nodeFor(String binaryName) {
            return Optional.ofNullable(nodesByClassName.get(binaryName));
        }

        Optional<LocalClassSemantics.ScopeDescriptor> scopeFor(Object astNode) {
            return Optional.ofNullable(scopesByNode.get(astNode));
        }
    }

    private static final class Builder {
        private final String rootBinaryName;
        private final List<LocalClassSemantics.ClassIdentity> classes = new ArrayList<>();
        private final List<LocalClassSemantics.ScopeDescriptor> scopes = new ArrayList<>();
        private final IdentityHashMap<Object, LocalClassSemantics.ClassIdentity> classesByNode =
                new IdentityHashMap<>();
        private final Map<String, Object> nodesByClassName = new LinkedHashMap<>();
        private final IdentityHashMap<Object, LocalClassSemantics.ScopeDescriptor> scopesByNode =
                new IdentityHashMap<>();
        private int nextScopeOrdinal;

        private Builder(String rootBinaryName) {
            this.rootBinaryName = rootBinaryName;
        }

        private void discoverRoot(TypeDeclaration root) {
            LocalClassSemantics.ClassIdentity identity = new LocalClassSemantics.ClassIdentity(
                    rootBinaryName, LocalClassSemantics.ClassKind.ROOT, Optional.of(root.name()),
                    Optional.empty(), 0, root.span());
            registerClass(root, identity);
            LocalClassSemantics.ScopeDescriptor typeScope = scope(root,
                    LocalClassSemantics.ScopeKind.TYPE_BODY, null, identity.binaryName(),
                    root.span(), root.isStatic());
            walkType(root, new ClassCursor(identity), typeScope);
        }

        private Result finish() {
            return new Result(rootBinaryName, classes, scopes, classesByNode,
                    nodesByClassName, scopesByNode);
        }

        private void walkType(TypeDeclaration declaration, ClassCursor cursor,
                              LocalClassSemantics.ScopeDescriptor typeScope) {
            if (declaration instanceof ClassDeclaration classDeclaration) {
                walkClassBody(classDeclaration, cursor, typeScope);
            } else {
                walkInterfaceBody((InterfaceDeclaration) declaration, cursor, typeScope);
            }
        }

        private void walkClassBody(ClassDeclaration declaration, ClassCursor cursor,
                                   LocalClassSemantics.ScopeDescriptor typeScope) {
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
            elements.stream().sorted(BodyElement.ORDER).forEach(element ->
                    walkBodyElement(element.node(), cursor, typeScope));
        }

        private void walkInterfaceBody(InterfaceDeclaration declaration, ClassCursor cursor,
                                       LocalClassSemantics.ScopeDescriptor typeScope) {
            List<BodyElement> elements = new ArrayList<>();
            declaration.fields().forEach(field ->
                    elements.add(new BodyElement(field, 0, field.span())));
            declaration.methods().forEach(method ->
                    elements.add(new BodyElement(method, 1, method.span())));
            declaration.memberTypes().forEach(type ->
                    elements.add(new BodyElement(type, 2, type.span())));
            elements.stream().sorted(BodyElement.ORDER).forEach(element ->
                    walkBodyElement(element.node(), cursor, typeScope));
        }

        private void walkAnonymousBody(AnonymousClassBody body, ClassCursor cursor,
                                       LocalClassSemantics.ScopeDescriptor typeScope) {
            List<BodyElement> elements = new ArrayList<>();
            body.fields().stream().filter(FieldDeclaration::isStatic)
                    .forEach(field -> elements.add(new BodyElement(field, 0, field.span())));
            body.instanceInitializations().forEach(initialization ->
                    elements.add(new BodyElement(initialization, 1, initialization.span())));
            body.methods().forEach(method ->
                    elements.add(new BodyElement(method, 2, method.span())));
            body.memberTypes().forEach(type ->
                    elements.add(new BodyElement(type, 3, type.span())));
            elements.stream().sorted(BodyElement.ORDER).forEach(element ->
                    walkBodyElement(element.node(), cursor, typeScope));
        }

        private void walkBodyElement(Object node, ClassCursor cursor,
                                     LocalClassSemantics.ScopeDescriptor parent) {
            if (node instanceof EnumConstant constant) {
                discoverEnumConstant(constant, cursor, parent);
            } else if (node instanceof FieldDeclaration field) {
                field.initializer().ifPresent(initializer -> {
                    LocalClassSemantics.ScopeDescriptor fieldScope = scope(field,
                            LocalClassSemantics.ScopeKind.FIELD_INITIALIZER, parent,
                            cursor.identity.binaryName(), field.span(), field.isStatic());
                    walkExpression(initializer, cursor, fieldScope);
                });
            } else if (node instanceof StaticInitializerBlock staticInitializer) {
                Block initializer = staticInitializer.block();
                LocalClassSemantics.ScopeDescriptor initializerScope = scope(initializer,
                        LocalClassSemantics.ScopeKind.STATIC_INITIALIZER, parent,
                        cursor.identity.binaryName(), initializer.span(), true);
                walkBlock(initializer, cursor, initializerScope);
            } else if (node instanceof Block initializer) {
                LocalClassSemantics.ScopeDescriptor initializerScope = scope(initializer,
                        LocalClassSemantics.ScopeKind.INSTANCE_INITIALIZER, parent,
                        cursor.identity.binaryName(), initializer.span(), false);
                walkBlock(initializer, cursor, initializerScope);
            } else if (node instanceof ConstructorDeclaration constructor) {
                LocalClassSemantics.ScopeDescriptor constructorScope = scope(constructor,
                        LocalClassSemantics.ScopeKind.CONSTRUCTOR, parent,
                        cursor.identity.binaryName(), constructor.span(), false);
                constructor.superInvocation().ifPresent(invocation ->
                        walkSuperInvocation(invocation, cursor, constructorScope));
                constructor.thisInvocation().ifPresent(invocation ->
                        invocation.arguments().forEach(argument ->
                                walkExpression(argument, cursor, constructorScope)));
                walkBlock(constructor.body(), cursor, constructorScope);
            } else if (node instanceof ironwood.compiler.ast.MethodDeclaration method) {
                LocalClassSemantics.ScopeDescriptor methodScope = scope(method,
                        LocalClassSemantics.ScopeKind.METHOD, parent,
                        cursor.identity.binaryName(), method.span(), method.isStatic());
                method.body().ifPresent(body -> walkBlock(body, cursor, methodScope));
            } else if (node instanceof InterfaceMethodDeclaration method) {
                LocalClassSemantics.ScopeDescriptor methodScope = scope(method,
                        LocalClassSemantics.ScopeKind.METHOD, parent,
                        cursor.identity.binaryName(), method.span(), method.isStatic());
                method.body().ifPresent(body -> walkBlock(body, cursor, methodScope));
            } else {
                discoverMember((TypeDeclaration) node, cursor, parent);
            }
        }

        private void discoverEnumConstant(EnumConstant constant, ClassCursor enclosing,
                                          LocalClassSemantics.ScopeDescriptor declarationScope) {
            LocalClassSemantics.ScopeDescriptor argumentScope = scope(constant,
                    LocalClassSemantics.ScopeKind.ANONYMOUS_ARGUMENTS, declarationScope,
                    enclosing.identity.binaryName(), constant.span(), true);
            constant.arguments().forEach(argument ->
                    walkExpression(argument, enclosing, argumentScope));
            if (constant.classBody().isEmpty()) {
                return;
            }
            int ordinal = enclosing.nextOrdinal++;
            String binaryName = enclosing.identity.binaryName() + "$" + ordinal;
            LocalClassSemantics.ClassIdentity identity = new LocalClassSemantics.ClassIdentity(
                    binaryName, LocalClassSemantics.ClassKind.ENUM_CONSTANT, Optional.empty(),
                    Optional.of(enclosing.identity.binaryName()), ordinal, constant.span());
            registerClass(constant, identity);
            AnonymousClassBody body = constant.classBody().orElseThrow();
            classesByNode.put(body, identity);
            LocalClassSemantics.ScopeDescriptor typeScope = scope(body,
                    LocalClassSemantics.ScopeKind.TYPE_BODY, declarationScope, binaryName,
                    body.span(), false);
            walkAnonymousBody(body, new ClassCursor(identity), typeScope);
        }

        private void discoverMember(TypeDeclaration member, ClassCursor enclosing,
                                    LocalClassSemantics.ScopeDescriptor parent) {
            String binaryName = enclosing.identity.binaryName() + "$" + member.name();
            LocalClassSemantics.ClassIdentity identity = new LocalClassSemantics.ClassIdentity(
                    binaryName, LocalClassSemantics.ClassKind.MEMBER, Optional.of(member.name()),
                    Optional.of(enclosing.identity.binaryName()), 0, member.span());
            registerClass(member, identity);
            LocalClassSemantics.ScopeDescriptor typeScope = scope(member,
                    LocalClassSemantics.ScopeKind.TYPE_BODY, parent, binaryName,
                    member.span(), member.isStatic());
            walkType(member, new ClassCursor(identity), typeScope);
        }

        private void walkBlock(Block block, ClassCursor cursor,
                               LocalClassSemantics.ScopeDescriptor parent) {
            LocalClassSemantics.ScopeDescriptor blockScope = scope(block,
                    LocalClassSemantics.ScopeKind.BLOCK, parent, cursor.identity.binaryName(),
                    block.span(), parent.staticContext());
            block.statements().forEach(statement -> walkStatement(statement, cursor, blockScope));
        }

        private void walkStatement(Statement statement, ClassCursor cursor,
                                   LocalClassSemantics.ScopeDescriptor scope) {
            if (statement instanceof Block block) {
                walkBlock(block, cursor, scope);
            } else if (statement instanceof LocalClassDeclaration local) {
                discoverLocal(local, cursor, scope);
            } else if (statement instanceof LocalVariableDeclaration local) {
                walkExpression(local.initializer(), cursor, scope);
            } else if (statement instanceof AssignmentStatement assignment) {
                walkExpression(assignment.target(), cursor, scope);
                walkExpression(assignment.value(), cursor, scope);
            } else if (statement instanceof ExpressionStatement expression) {
                walkExpression(expression.expression(), cursor, scope);
            } else if (statement instanceof FreeStatement free) {
                walkExpression(free.value(), cursor, scope);
            } else if (statement instanceof ReturnStatement returned) {
                returned.value().ifPresent(value -> walkExpression(value, cursor, scope));
            } else if (statement instanceof ThrowStatement thrown) {
                walkExpression(thrown.value(), cursor, scope);
            } else if (statement instanceof YieldStatement yielded) {
                walkExpression(yielded.value(), cursor, scope);
            } else if (statement instanceof SuperConstructorInvocation invocation) {
                walkSuperInvocation(invocation, cursor, scope);
            } else if (statement instanceof ThisConstructorInvocation invocation) {
                invocation.arguments().forEach(argument -> walkExpression(argument, cursor, scope));
            } else if (statement instanceof IfStatement conditional) {
                walkExpression(conditional.condition(), cursor, scope);
                walkScopedStatement(conditional.thenBranch(), cursor, scope,
                        LocalClassSemantics.ScopeKind.BRANCH);
                conditional.elseBranch().ifPresent(branch -> walkScopedStatement(
                        branch, cursor, scope, LocalClassSemantics.ScopeKind.BRANCH));
            } else if (statement instanceof WhileStatement loop) {
                LocalClassSemantics.ScopeDescriptor loopScope = scope(loop,
                        LocalClassSemantics.ScopeKind.LOOP, scope, cursor.identity.binaryName(),
                        loop.span(), scope.staticContext());
                walkExpression(loop.condition(), cursor, loopScope);
                walkScopedStatement(loop.body(), cursor, loopScope,
                        LocalClassSemantics.ScopeKind.BRANCH);
            } else if (statement instanceof DoWhileStatement loop) {
                LocalClassSemantics.ScopeDescriptor loopScope = scope(loop,
                        LocalClassSemantics.ScopeKind.LOOP, scope, cursor.identity.binaryName(),
                        loop.span(), scope.staticContext());
                walkScopedStatement(loop.body(), cursor, loopScope,
                        LocalClassSemantics.ScopeKind.BRANCH);
                walkExpression(loop.condition(), cursor, loopScope);
            } else if (statement instanceof ForStatement loop) {
                LocalClassSemantics.ScopeDescriptor loopScope = scope(loop,
                        LocalClassSemantics.ScopeKind.LOOP, scope, cursor.identity.binaryName(),
                        loop.span(), scope.staticContext());
                loop.initializer().ifPresent(initializer ->
                        walkStatement(initializer, cursor, loopScope));
                loop.condition().ifPresent(condition -> walkExpression(condition, cursor, loopScope));
                walkScopedStatement(loop.body(), cursor, loopScope,
                        LocalClassSemantics.ScopeKind.BRANCH);
                loop.updates().forEach(update -> walkExpression(update, cursor, loopScope));
            } else if (statement instanceof EnhancedForStatement loop) {
                LocalClassSemantics.ScopeDescriptor loopScope = scope(loop,
                        LocalClassSemantics.ScopeKind.LOOP, scope, cursor.identity.binaryName(),
                        loop.span(), scope.staticContext());
                walkExpression(loop.iterable(), cursor, loopScope);
                walkScopedStatement(loop.body(), cursor, loopScope,
                        LocalClassSemantics.ScopeKind.BRANCH);
            } else if (statement instanceof LabeledStatement labeled) {
                walkScopedStatement(labeled.body(), cursor, scope,
                        LocalClassSemantics.ScopeKind.BRANCH);
            } else if (statement instanceof EmptyStatement) {
                // No lexical declarations.
            } else if (statement instanceof SwitchStatement switched) {
                LocalClassSemantics.ScopeDescriptor switchScope = scope(switched,
                        LocalClassSemantics.ScopeKind.SWITCH, scope, cursor.identity.binaryName(),
                        switched.span(), scope.staticContext());
                walkExpression(switched.selector(), cursor, switchScope);
                switched.groups().forEach(group -> group.labels().forEach(label ->
                        label.value().ifPresent(value ->
                                walkExpression(value, cursor, switchScope))));
                switched.groups().forEach(group -> group.statements().forEach(child ->
                        walkStatement(child, cursor, switchScope)));
            } else if (statement instanceof ModernSwitchStatement switched) {
                LocalClassSemantics.ScopeDescriptor switchScope = scope(switched,
                        LocalClassSemantics.ScopeKind.SWITCH, scope, cursor.identity.binaryName(),
                        switched.span(), scope.staticContext());
                walkExpression(switched.selector(), cursor, switchScope);
                walkSwitchRules(switched.rules(), cursor, switchScope);
            } else if (statement instanceof TryStatement guarded) {
                LocalClassSemantics.ScopeDescriptor tryScope = scope(guarded,
                        LocalClassSemantics.ScopeKind.TRY, scope, cursor.identity.binaryName(),
                        guarded.span(), scope.staticContext());
                walkBlock(guarded.body(), cursor, tryScope);
                guarded.catches().forEach(caught -> {
                    LocalClassSemantics.ScopeDescriptor catchScope = scope(caught,
                            LocalClassSemantics.ScopeKind.CATCH, tryScope,
                            cursor.identity.binaryName(), caught.span(), scope.staticContext());
                    walkBlock(caught.body(), cursor, catchScope);
                });
                guarded.finallyBlock().ifPresent(cleanup -> {
                    LocalClassSemantics.ScopeDescriptor finallyScope = scope(cleanup,
                            LocalClassSemantics.ScopeKind.FINALLY, tryScope,
                            cursor.identity.binaryName(), cleanup.span(), scope.staticContext());
                    walkBlock(cleanup, cursor, finallyScope);
                });
            }
        }

        private void walkScopedStatement(Statement statement, ClassCursor cursor,
                                         LocalClassSemantics.ScopeDescriptor parent,
                                         LocalClassSemantics.ScopeKind kind) {
            LocalClassSemantics.ScopeDescriptor scoped = scope(statement, kind, parent,
                    cursor.identity.binaryName(), statement.span(), parent.staticContext());
            walkStatement(statement, cursor, scoped);
        }

        private void discoverLocal(LocalClassDeclaration local, ClassCursor enclosing,
                                   LocalClassSemantics.ScopeDescriptor declarationScope) {
            int ordinal = enclosing.nextOrdinal++;
            String binaryName = enclosing.identity.binaryName() + "$" + ordinal
                    + local.declaration().name();
            LocalClassSemantics.ClassIdentity identity = new LocalClassSemantics.ClassIdentity(
                    binaryName, LocalClassSemantics.ClassKind.LOCAL,
                    Optional.of(local.declaration().name()),
                    Optional.of(enclosing.identity.binaryName()), ordinal, local.span());
            registerClass(local, identity);
            classesByNode.put(local.declaration(), identity);
            LocalClassSemantics.ScopeDescriptor typeScope = scope(local.declaration(),
                    LocalClassSemantics.ScopeKind.TYPE_BODY, declarationScope, binaryName,
                    local.span(), declarationScope.staticContext());
            walkClassBody(local.declaration(), new ClassCursor(identity), typeScope);
        }

        private void walkExpression(Expression expression, ClassCursor cursor,
                                    LocalClassSemantics.ScopeDescriptor scope) {
            if (expression instanceof SwitchExpression switched) {
                LocalClassSemantics.ScopeDescriptor switchScope = scope(switched,
                        LocalClassSemantics.ScopeKind.SWITCH, scope, cursor.identity.binaryName(),
                        switched.span(), scope.staticContext());
                walkExpression(switched.selector(), cursor, switchScope);
                if (switched.arrowRules()) {
                    walkSwitchRules(switched.rules(), cursor, switchScope);
                } else {
                    switched.groups().forEach(group -> {
                        group.labels().forEach(label -> label.value().ifPresent(value ->
                                walkExpression(value, cursor, switchScope)));
                        group.statements().forEach(child ->
                                walkStatement(child, cursor, switchScope));
                    });
                }
            } else if (expression instanceof NewExpression allocation) {
                if (allocation.anonymousClassBody().isPresent()) {
                    discoverAnonymous(allocation, cursor, scope);
                } else {
                    allocation.enclosingInstance().ifPresent(enclosing ->
                            walkExpression(enclosing, cursor, scope));
                    allocation.arguments().forEach(argument -> walkExpression(argument, cursor, scope));
                }
            } else if (expression instanceof ArrayCreationExpression creation) {
                creation.length().ifPresent(length -> walkExpression(length, cursor, scope));
                creation.initializer().ifPresent(initializer ->
                        walkExpression(initializer, cursor, scope));
            } else if (expression instanceof ArrayInitializerExpression initializer) {
                initializer.elements().forEach(element ->
                        walkExpression(element, cursor, scope));
            } else if (expression instanceof ArrayAccessExpression access) {
                walkExpression(access.array(), cursor, scope);
                walkExpression(access.index(), cursor, scope);
            } else if (expression instanceof AssignmentExpression assignment) {
                walkExpression(assignment.target(), cursor, scope);
                walkExpression(assignment.value(), cursor, scope);
            } else if (expression instanceof BinaryExpression binary) {
                walkExpression(binary.left(), cursor, scope);
                walkExpression(binary.right(), cursor, scope);
            } else if (expression instanceof CallExpression call) {
                call.receiver().ifPresent(receiver -> walkExpression(receiver, cursor, scope));
                call.arguments().forEach(argument -> walkExpression(argument, cursor, scope));
            } else if (expression instanceof CastExpression cast) {
                walkExpression(cast.operand(), cursor, scope);
            } else if (expression instanceof ConditionalExpression conditional) {
                walkExpression(conditional.condition(), cursor, scope);
                walkExpression(conditional.whenTrue(), cursor, scope);
                walkExpression(conditional.whenFalse(), cursor, scope);
            } else if (expression instanceof FieldAccessExpression access) {
                walkExpression(access.receiver(), cursor, scope);
            } else if (expression instanceof InstanceOfExpression typeTest) {
                walkExpression(typeTest.operand(), cursor, scope);
            } else if (expression instanceof QualifiedSuperConstructorExpression invocation) {
                walkExpression(invocation.enclosingInstance(), cursor, scope);
                invocation.arguments().forEach(argument -> walkExpression(argument, cursor, scope));
            } else if (expression instanceof UnaryExpression unary) {
                walkExpression(unary.operand(), cursor, scope);
            } else if (expression instanceof UpdateExpression update) {
                walkExpression(update.target(), cursor, scope);
            }
        }

        private void walkSwitchRules(List<ironwood.compiler.ast.SwitchRule> rules,
                                     ClassCursor cursor,
                                     LocalClassSemantics.ScopeDescriptor scope) {
            rules.forEach(rule -> {
                rule.labels().forEach(label -> label.value().ifPresent(value ->
                        walkExpression(value, cursor, scope)));
                if (rule.body() instanceof SwitchRuleExpression result) {
                    walkExpression(result.expression(), cursor, scope);
                } else if (rule.body() instanceof SwitchRuleBlock block) {
                    walkBlock(block.block(), cursor, scope);
                } else if (rule.body() instanceof SwitchRuleThrow thrown) {
                    walkStatement(thrown.statement(), cursor, scope);
                }
            });
        }

        private void discoverAnonymous(NewExpression allocation, ClassCursor enclosing,
                                       LocalClassSemantics.ScopeDescriptor declarationScope) {
            int ordinal = enclosing.nextOrdinal++;
            String binaryName = enclosing.identity.binaryName() + "$" + ordinal;
            LocalClassSemantics.ClassIdentity identity = new LocalClassSemantics.ClassIdentity(
                    binaryName, LocalClassSemantics.ClassKind.ANONYMOUS, Optional.empty(),
                    Optional.of(enclosing.identity.binaryName()), ordinal, allocation.span());
            registerClass(allocation, identity);
            LocalClassSemantics.ScopeDescriptor argumentScope = scope(allocation,
                    LocalClassSemantics.ScopeKind.ANONYMOUS_ARGUMENTS, declarationScope,
                    enclosing.identity.binaryName(), allocation.span(),
                    declarationScope.staticContext());
            allocation.enclosingInstance().ifPresent(enclosingInstance ->
                    walkExpression(enclosingInstance, enclosing, argumentScope));
            allocation.arguments().forEach(argument ->
                    walkExpression(argument, enclosing, argumentScope));
            AnonymousClassBody body = allocation.anonymousClassBody().orElseThrow();
            LocalClassSemantics.ScopeDescriptor typeScope = scope(body,
                    LocalClassSemantics.ScopeKind.TYPE_BODY, declarationScope, binaryName,
                    body.span(), declarationScope.staticContext());
            walkAnonymousBody(body, new ClassCursor(identity), typeScope);
        }

        private void walkSuperInvocation(SuperConstructorInvocation invocation, ClassCursor cursor,
                                         LocalClassSemantics.ScopeDescriptor scope) {
            invocation.enclosingInstance().ifPresent(enclosing ->
                    walkExpression(enclosing, cursor, scope));
            invocation.arguments().forEach(argument -> walkExpression(argument, cursor, scope));
        }

        private void registerClass(Object node, LocalClassSemantics.ClassIdentity identity) {
            classes.add(identity);
            classesByNode.put(node, identity);
            nodesByClassName.put(identity.binaryName(), node);
        }

        private LocalClassSemantics.ScopeDescriptor scope(
                Object node, LocalClassSemantics.ScopeKind kind,
                LocalClassSemantics.ScopeDescriptor parent, String ownerBinaryName,
                SourceSpan span, boolean staticContext) {
            LocalClassSemantics.ScopeDescriptor existing = scopesByNode.get(node);
            if (existing != null && existing.kind() == kind
                    && existing.ownerBinaryName().equals(ownerBinaryName)) {
                return existing;
            }
            String id = ownerBinaryName + "#scope" + nextScopeOrdinal++ + ":"
                    + kind.name().toLowerCase(java.util.Locale.ROOT) + "@" + span.start().offset();
            LocalClassSemantics.ScopeDescriptor descriptor =
                    new LocalClassSemantics.ScopeDescriptor(id, kind,
                            Optional.ofNullable(parent).map(LocalClassSemantics.ScopeDescriptor::id),
                            ownerBinaryName, span, staticContext);
            scopes.add(descriptor);
            scopesByNode.putIfAbsent(node, descriptor);
            return descriptor;
        }
    }

    private static final class ClassCursor {
        private final LocalClassSemantics.ClassIdentity identity;
        private int nextOrdinal = 1;

        private ClassCursor(LocalClassSemantics.ClassIdentity identity) {
            this.identity = identity;
        }
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
