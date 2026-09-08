// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.ArrayAccessExpression;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.AssignmentExpression;
import ironwood.compiler.ast.AssignmentOperator;
import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.BreakStatement;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.ContinueStatement;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EmptyStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.InstanceOfExpression;
import ironwood.compiler.ast.InstanceInitialization;
import ironwood.compiler.ast.InterfaceSuperExpression;
import ironwood.compiler.ast.LocalClassDeclaration;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.QualifiedThisExpression;
import ironwood.compiler.ast.QualifiedSuperConstructorExpression;
import ironwood.compiler.ast.NullLiteralExpression;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.SuperConstructorInvocation;
import ironwood.compiler.ast.SuperExpression;
import ironwood.compiler.ast.SwitchExpression;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchRuleThrow;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ast.ThisExpression;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UpdateExpression;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;
import ironwood.compiler.ir.IrType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Finds private reference fields whose allocation identity remains encapsulated by their owner.
 *
 * <p>Every write must install a directly-created object or array,
 * and no read of the field (or local alias of that read) may be thrown, stored in another
 * location, or passed to a call that may retain it. A direct, summarized return may lend an
 * encapsulated helper as a dependent borrow while leaving ownership with the field's containing
 * object. Closed-world escape summaries admit calls whose resolved target only observes the
 * reference.</p>
 */
final class OwnedArrayFieldAnalyzer {
    private final Map<String, TypeSymbol> types;
    private final ClassHierarchy hierarchy;
    private final EscapeSummaryAnalyzer escapeSummaries;
    private final Set<String> ownedFields = new LinkedHashSet<>();
    private final Map<String, FieldSymbol> borrowedReturnFields = new LinkedHashMap<>();
    private final Set<String> ambiguousBorrowedReturns = new LinkedHashSet<>();
    private final Map<String, String> rejectionReasons = new LinkedHashMap<>();
    private final Map<String, Boolean> encapsulatedFields = new LinkedHashMap<>();

    OwnedArrayFieldAnalyzer(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                            EscapeSummaryAnalyzer escapeSummaries) {
        this.types = types;
        this.hierarchy = hierarchy;
        this.escapeSummaries = escapeSummaries;
        for (TypeSymbol type : types.values()) {
            if (type.isInterface()) {
                continue;
            }
            for (FieldSymbol field : type.declaredFields().values()) {
                boolean auditedByteBufferArrayLoan = field.ownerClass()
                        .equals("ironwood.nio.ByteBuffer")
                        && field.declaration().name().equals("ownedStorage")
                        && field.type().isArray();
                Checker checker = new Checker(type, field, true,
                        field.type().isNominalReference() || auditedByteBufferArrayLoan);
                if (!field.isStatic()
                        && field.accessModifier() == ironwood.compiler.ast.AccessModifier.PRIVATE
                        && field.type().isReference()
                        && checker.isOwned()) {
                    ownedFields.add(key(field));
                    for (String linkageName : checker.borrowedReturnMethods) {
                        FieldSymbol previous = borrowedReturnFields.putIfAbsent(linkageName, field);
                        if (previous != null && previous != field) {
                            borrowedReturnFields.remove(linkageName);
                            ambiguousBorrowedReturns.add(linkageName);
                        }
                    }
                } else if (checker.rejectionReason != null) {
                    rejectionReasons.put(key(field), checker.rejectionReason);
                }
            }
        }
    }

    boolean isOwned(FieldSymbol field) {
        return ownedFields.contains(key(field));
    }

    String rejectionReason(FieldSymbol field) {
        return rejectionReasons.get(key(field));
    }

    FieldSymbol borrowedReturnField(CallableSymbol callable) {
        return borrowedReturnField(callable.linkageName());
    }

    FieldSymbol borrowedReturnField(String linkageName) {
        return ambiguousBorrowedReturns.contains(linkageName)
                ? null : borrowedReturnFields.get(linkageName);
    }

    boolean isEncapsulated(FieldSymbol field) {
        return encapsulatedFields.computeIfAbsent(key(field), ignored -> {
            TypeSymbol owner = types.get(field.ownerClass());
            return owner != null && !field.isStatic()
                    && field.accessModifier() == ironwood.compiler.ast.AccessModifier.PRIVATE
                    && field.type().isReference()
                    && new Checker(owner, field, false, false).isOwned();
        });
    }

    java.util.List<FieldSymbol> ownedInstanceFields(TypeSymbol type) {
        java.util.ArrayList<FieldSymbol> result = new java.util.ArrayList<>();
        for (var layout : type.layoutFields()) {
            TypeSymbol owner = types.get(layout.ownerClass());
            FieldSymbol field = owner == null ? null
                    : owner.declaredFields().get(layout.name());
            if (field != null && layout.equals(field.irField()) && isOwned(field)) {
                result.add(field);
            }
        }
        return java.util.List.copyOf(result);
    }

    private static String key(FieldSymbol field) {
        return field.ownerClass() + "#" + field.declaration().name();
    }

    private final class Checker {
        private final TypeSymbol owner;
        private final FieldSymbol candidate;
        private final boolean requireFreshWrites;
        private final boolean allowBorrowedReturns;
        private boolean owned = true;
        private String rejectionReason;
        private boolean staticFunction;
        private CallableSymbol currentCallable;
        private FieldSymbol constructionTarget;
        private final Set<String> borrowedReturnMethods = new LinkedHashSet<>();
        private final Set<String> nonBorrowedReturnMethods = new LinkedHashSet<>();
        private final Deque<boolean[]> switchYieldOrigins = new ArrayDeque<>();

        private Checker(TypeSymbol owner, FieldSymbol candidate,
                        boolean requireFreshWrites, boolean allowBorrowedReturns) {
            this.owner = owner;
            this.candidate = candidate;
            this.requireFreshWrites = requireFreshWrites;
            this.allowBorrowedReturns = allowBorrowedReturns;
        }

        private boolean isOwned() {
            FieldDeclaration declaration = candidate.declaration();
            declaration.initializer().ifPresent(initializer -> {
                if (requireFreshWrites && !isFreshValue(initializer)) {
                    reject();
                }
            });
            staticFunction = false;
            Map<String, Boolean> initializerEnvironment = new LinkedHashMap<>();
            for (InstanceInitialization initialization
                    : ((ironwood.compiler.ast.ClassDeclaration) owner.declaration())
                    .instanceInitializations()) {
                if (initialization instanceof FieldDeclaration field) {
                    if (field != declaration) {
                        field.initializer().ifPresent(expression -> {
                            if (origin(expression, initializerEnvironment)) {
                                reject();
                            }
                        });
                    }
                } else {
                    scanBlock((Block) initialization, initializerEnvironment, true);
                }
            }
            owner.constructors().forEach(this::scanCallable);
            owner.declaredMethods().values().forEach(this::scanCallable);
            for (TypeSymbol nestMate : types.values()) {
                if (nestMate == owner || !owner.sameNest(nestMate)) {
                    continue;
                }
                if (!nestMate.isInterface()) {
                    scanNestMateInitializers(nestMate);
                    nestMate.constructors().forEach(this::scanCallable);
                }
                nestMate.declaredMethods().values().forEach(this::scanCallable);
            }
            borrowedReturnMethods.removeAll(nonBorrowedReturnMethods);
            return owned;
        }

        private void scanNestMateInitializers(TypeSymbol nestMate) {
            staticFunction = false;
            Map<String, Boolean> environment = new LinkedHashMap<>();
            for (InstanceInitialization initialization
                    : ((ironwood.compiler.ast.ClassDeclaration) nestMate.declaration())
                    .instanceInitializations()) {
                if (initialization instanceof FieldDeclaration field) {
                    field.initializer().ifPresent(expression -> {
                        if (origin(expression, environment)) {
                            reject();
                        }
                    });
                } else {
                    scanBlock((Block) initialization, environment, true);
                }
            }
        }

        private void scanCallable(CallableSymbol callable) {
            CallableSymbol previousCallable = currentCallable;
            currentCallable = callable;
            Map<String, Boolean> environment = new LinkedHashMap<>();
            callable.parameters().forEach(parameter -> environment.put(parameter.name(), false));
            staticFunction = callable.isStatic();
            callable.superInvocation().ifPresent(invocation -> invocation.arguments().forEach(argument -> {
                if (origin(argument, environment)) {
                    reject();
                }
            }));
            callable.thisInvocation().ifPresent(invocation -> invocation.arguments().forEach(argument -> {
                if (origin(argument, environment)) {
                    reject();
                }
            }));
            callable.body().ifPresent(body -> scanBlock(body, environment, false));
            currentCallable = previousCallable;
        }

        private void scanBlock(Block block, Map<String, Boolean> environment, boolean scoped) {
            Set<String> existing = Set.copyOf(environment.keySet());
            for (Statement statement : block.statements()) {
                scanStatement(statement, environment);
            }
            if (scoped) {
                environment.keySet().removeIf(name -> !existing.contains(name));
            }
        }

        private void scanStatement(Statement statement, Map<String, Boolean> environment) {
            if (statement instanceof Block block) {
                scanBlock(block, environment, true);
                return;
            }
            if (statement instanceof LocalVariableDeclaration declaration) {
                environment.put(declaration.name(), origin(declaration.initializer(), environment));
                return;
            }
            if (statement instanceof LocalClassDeclaration) {
                return;
            }
            if (statement instanceof AssignmentStatement assignment) {
                assign(assignment.target(), assignment.value(), environment,
                        isFreshValue(assignment.value()));
                return;
            }
            if (statement instanceof ExpressionStatement expression) {
                origin(expression.expression(), environment);
                return;
            }
            if (statement instanceof FreeStatement free) {
                origin(free.value(), environment);
                return;
            }
            if (statement instanceof ReturnStatement returned) {
                returned.value().ifPresent(value -> {
                    if (origin(value, environment)) {
                        if (allowBorrowedReturns && !staticFunction && currentCallable != null
                                && isBorrowedReturnExpression(value)) {
                            borrowedReturnMethods.add(currentCallable.linkageName());
                        } else {
                            reject();
                        }
                    } else if (currentCallable != null) {
                        nonBorrowedReturnMethods.add(currentCallable.linkageName());
                    }
                });
                return;
            }
            if (statement instanceof ThrowStatement thrown) {
                if (origin(thrown.value(), environment)) {
                    reject();
                }
                return;
            }
            if (statement instanceof YieldStatement yielded) {
                boolean attached = origin(yielded.value(), environment);
                if (!switchYieldOrigins.isEmpty()) {
                    switchYieldOrigins.peek()[0] |= attached;
                }
                return;
            }
            if (statement instanceof SuperConstructorInvocation invocation) {
                scanInvocationArguments(invocation, environment);
                return;
            }
            if (statement instanceof ThisConstructorInvocation invocation) {
                scanInvocationArguments(invocation, environment);
                return;
            }
            if (statement instanceof IfStatement conditional) {
                origin(conditional.condition(), environment);
                Map<String, Boolean> whenTrue = copy(environment);
                scanScopedStatement(conditional.thenBranch(), whenTrue);
                Map<String, Boolean> whenFalse = copy(environment);
                conditional.elseBranch().ifPresent(branch -> scanScopedStatement(branch, whenFalse));
                merge(environment, whenTrue, whenFalse);
                return;
            }
            if (statement instanceof WhileStatement loop) {
                origin(loop.condition(), environment);
                Map<String, Boolean> body = copy(environment);
                scanScopedStatement(loop.body(), body);
                merge(environment, environment, body);
                return;
            }
            if (statement instanceof DoWhileStatement loop) {
                Map<String, Boolean> body = copy(environment);
                scanScopedStatement(loop.body(), body);
                origin(loop.condition(), body);
                merge(environment, environment, body);
                return;
            }
            if (statement instanceof ForStatement loop) {
                Map<String, Boolean> loopEnvironment = copy(environment);
                loop.initializer().ifPresent(initializer -> scanStatement(initializer, loopEnvironment));
                loop.condition().ifPresent(condition -> origin(condition, loopEnvironment));
                Map<String, Boolean> body = copy(loopEnvironment);
                scanScopedStatement(loop.body(), body);
                loop.updates().forEach(update -> origin(update, body));
                merge(environment, environment, body);
                return;
            }
            if (statement instanceof EnhancedForStatement loop) {
                origin(loop.iterable(), environment);
                Map<String, Boolean> body = copy(environment);
                body.put(loop.variableName(), false);
                scanScopedStatement(loop.body(), body);
                merge(environment, environment, body);
                return;
            }
            if (statement instanceof LabeledStatement labeled) {
                scanScopedStatement(labeled.body(), environment);
                return;
            }
            if (statement instanceof EmptyStatement) {
                return;
            }
            if (statement instanceof SwitchStatement switched) {
                origin(switched.selector(), environment);
                Map<String, Boolean> merged = copy(environment);
                Map<String, Boolean> fallthrough = copy(environment);
                for (var group : switched.groups()) {
                    Map<String, Boolean> groupEnvironment = copy(environment);
                    merge(groupEnvironment, groupEnvironment, fallthrough);
                    for (Statement child : group.statements()) {
                        scanStatement(child, groupEnvironment);
                    }
                    merge(merged, merged, groupEnvironment);
                    fallthrough = groupEnvironment;
                }
                environment.clear();
                environment.putAll(merged);
                return;
            }
            if (statement instanceof ModernSwitchStatement switched) {
                origin(switched.selector(), environment);
                Map<String, Boolean> merged = copy(environment);
                for (var rule : switched.rules()) {
                    Map<String, Boolean> branch = copy(environment);
                    scanSwitchRuleBody(rule.body(), branch);
                    merge(merged, merged, branch);
                }
                environment.clear();
                environment.putAll(merged);
                return;
            }
            if (statement instanceof BreakStatement || statement instanceof ContinueStatement) {
                return;
            }
            TryStatement guarded = (TryStatement) statement;
            Map<String, Boolean> merged = copy(environment);
            Map<String, Boolean> body = copy(environment);
            scanBlock(guarded.body(), body, true);
            merge(merged, merged, body);
            guarded.catches().forEach(caught -> {
                Map<String, Boolean> caughtEnvironment = copy(environment);
                caughtEnvironment.put(caught.variableName(), false);
                scanBlock(caught.body(), caughtEnvironment, true);
                merge(merged, merged, caughtEnvironment);
            });
            guarded.finallyBlock().ifPresent(cleanup -> scanBlock(cleanup, merged, true));
            environment.clear();
            environment.putAll(merged);
        }

        private void scanInvocationArguments(SuperConstructorInvocation invocation,
                                             Map<String, Boolean> environment) {
            invocation.enclosingInstance().ifPresent(enclosing -> {
                if (origin(enclosing, environment)) {
                    reject();
                }
            });
            invocation.arguments().forEach(argument -> {
                if (origin(argument, environment)) {
                    reject();
                }
            });
        }

        private void scanInvocationArguments(ThisConstructorInvocation invocation,
                                             Map<String, Boolean> environment) {
            invocation.arguments().forEach(argument -> {
                if (origin(argument, environment)) {
                    reject();
                }
            });
        }

        private void scanScopedStatement(Statement statement, Map<String, Boolean> environment) {
            Set<String> existing = Set.copyOf(environment.keySet());
            scanStatement(statement, environment);
            environment.keySet().removeIf(name -> !existing.contains(name));
        }

        private boolean origin(Expression expression, Map<String, Boolean> environment) {
            if (expression instanceof SwitchExpression switched) {
                origin(switched.selector(), environment);
                Map<String, Boolean> merged = copy(environment);
                boolean[] yieldedOrigin = new boolean[1];
                switchYieldOrigins.push(yieldedOrigin);
                if (switched.arrowRules()) {
                    for (var rule : switched.rules()) {
                        Map<String, Boolean> branch = copy(environment);
                        if (rule.body() instanceof SwitchRuleExpression result) {
                            yieldedOrigin[0] |= origin(result.expression(), branch);
                        } else {
                            scanSwitchRuleBody(rule.body(), branch);
                        }
                        merge(merged, merged, branch);
                    }
                } else {
                    Map<String, Boolean> fallthrough = copy(environment);
                    for (var group : switched.groups()) {
                        Map<String, Boolean> branch = copy(environment);
                        merge(branch, branch, fallthrough);
                        group.statements().forEach(child -> scanStatement(child, branch));
                        merge(merged, merged, branch);
                        fallthrough = branch;
                    }
                }
                switchYieldOrigins.pop();
                environment.clear();
                environment.putAll(merged);
                return yieldedOrigin[0];
            }
            if (expression instanceof NameExpression name) {
                if (environment.containsKey(name.name())) {
                    return environment.get(name.name());
                }
                return !staticFunction && name.name().equals(candidate.declaration().name());
            }
            if (expression instanceof ThisExpression || expression instanceof SuperExpression
                    || expression instanceof InterfaceSuperExpression
                    || expression instanceof QualifiedThisExpression) {
                return false;
            }
            if (expression instanceof FieldAccessExpression access) {
                if (isCandidateField(access)) {
                    return true;
                }
                if (access.fieldName().equals(candidate.declaration().name())) {
                    // Same-class code may inspect another instance's private array. Preserve
                    // the origin so returning, storing, or passing that container still rejects
                    // ownership, while element access and length remain safe observations.
                    origin(access.receiver(), environment);
                    return true;
                }
                origin(access.receiver(), environment);
                return false;
            }
            if (expression instanceof ArrayAccessExpression access) {
                boolean attachedArray = origin(access.array(), environment);
                if (attachedArray && containsReentrantExpression(access.index())) {
                    reject();
                }
                origin(access.index(), environment);
                return false;
            }
            if (expression instanceof ArrayCreationExpression creation) {
                creation.length().ifPresent(length -> origin(length, environment));
                creation.initializer().ifPresent(initializer ->
                        origin(initializer, environment));
                return false;
            }
            if (expression instanceof ArrayInitializerExpression initializer) {
                initializer.elements().forEach(element -> origin(element, environment));
                return false;
            }
            if (expression instanceof NewExpression allocation) {
                if (environment.containsValue(true)) {
                    reject();
                }
                allocation.enclosingInstance().ifPresent(enclosing -> {
                    if (origin(enclosing, environment)) {
                        reject();
                    }
                });
                CallableSymbol constructor = resolveConstructor(allocation, environment);
                for (int index = 0; index < allocation.arguments().size(); index++) {
                    Expression argument = allocation.arguments().get(index);
                    boolean attached = origin(argument, environment);
                    if (attached && (constructor == null
                            || escapeSummaries.summary(constructor).parameterEscapes(index))
                            && !isContainedEntryBuilderBorrow(constructor, index)) {
                        reject();
                    }
                }
                return false;
            }
            if (expression instanceof QualifiedSuperConstructorExpression invocation) {
                if (origin(invocation.enclosingInstance(), environment)) {
                    reject();
                }
                invocation.arguments().forEach(argument -> {
                    if (origin(argument, environment)) {
                        reject();
                    }
                });
                return false;
            }
            if (expression instanceof CallExpression call) {
                boolean arrayCopy = isSystemArrayCopy(call, environment);
                boolean receiverAttached = call.receiver().isPresent()
                        && origin(call.receiver().orElseThrow(), environment);
                java.util.List<Boolean> attachedArguments = call.arguments().stream()
                        .map(argument -> origin(argument, environment)).toList();
                if (arrayCopy) {
                    return false;
                }
                if (environment.containsValue(true)) {
                    reject();
                }
                if (isKnownBorrowingStreamRead(call)) {
                    return false;
                }
                if (currentCallable != null
                        && escapeSummaries.isNonRetainingPrimitiveCall(owner, currentCallable,
                                call, environment.keySet())) {
                    return false;
                }
                CallableSymbol target = receiverAttached
                        ? resolveAttachedCall(call) : resolveStaticCall(call, environment);
                if (target == null) {
                    if (receiverAttached || attachedArguments.contains(true)) {
                        reject();
                    }
                    return false;
                }
                EscapeSummaryAnalyzer.EscapeSummary summary = escapeSummaries.summary(target);
                ReturnOrigin exactReturn = !summary.mayReturnNonOrigin()
                        && summary.borrowedReturnedOrigins().isEmpty()
                        && summary.returnedOrigins().size() == 1
                        ? summary.returnedOrigins().iterator().next() : null;
                boolean entryPoolCall = DataStructureSemantics.isEntryPool(candidate)
                        && (PoolSemantics.isRelease(target) || PoolSemantics.isCheckout(target));
                if (receiverAttached && !entryPoolCall && (exactReturn != null
                        ? summary.thisEscapesWithoutReturn()
                        : summary.thisEscapes() || summary.thisEscapesWithoutReturn())) {
                    reject();
                }
                for (int index = 0; index < attachedArguments.size(); index++) {
                    if (attachedArguments.get(index) && (exactReturn != null
                            ? summary.parameterEscapesWithoutReturn(index)
                            : summary.parameterEscapes(index)
                            || summary.parameterEscapesWithoutReturn(index))) {
                        reject();
                    }
                }
                if (receiverAttached && summary.returnedOrigins().stream()
                        .anyMatch(returned -> returned.kind() == ReturnOrigin.Kind.THIS)) {
                    return true;
                }
                for (ReturnOrigin returned : summary.returnedOrigins()) {
                    if (returned.kind() == ReturnOrigin.Kind.PARAMETER
                            && returned.parameterIndex() < attachedArguments.size()
                            && attachedArguments.get(returned.parameterIndex())) {
                        return true;
                    }
                }
                return false;
            }
            if (expression instanceof BinaryExpression binary) {
                boolean attachedLeft = origin(binary.left(), environment);
                if (attachedLeft && containsReentrantExpression(binary.right())) {
                    reject();
                }
                origin(binary.right(), environment);
                return false;
            }
            if (expression instanceof AssignmentExpression assignment) {
                if (assignment.target() instanceof ArrayAccessExpression access
                        && origin(access.array(), environment)
                        && (containsReentrantExpression(access.index())
                        || containsReentrantExpression(assignment.value()))) {
                    reject();
                }
                boolean valueOrigin = assignmentOrigin(assignment.target(),
                        assignment.value(), environment);
                assignKnownOrigin(assignment.target(), valueOrigin, environment,
                        assignment.operator() == AssignmentOperator.ASSIGN
                                && isFreshValue(assignment.value()));
                return assignment.operator() == AssignmentOperator.ASSIGN && valueOrigin;
            }
            if (expression instanceof ConditionalExpression conditional) {
                origin(conditional.condition(), environment);
                Map<String, Boolean> whenTrue = copy(environment);
                boolean trueOrigin = origin(conditional.whenTrue(), whenTrue);
                Map<String, Boolean> whenFalse = copy(environment);
                boolean falseOrigin = origin(conditional.whenFalse(), whenFalse);
                merge(environment, whenTrue, whenFalse);
                return trueOrigin || falseOrigin;
            }
            if (expression instanceof CastExpression cast) {
                return origin(cast.operand(), environment);
            }
            if (expression instanceof UpdateExpression update) {
                origin(update.target(), environment);
                return false;
            }
            if (expression instanceof UnaryExpression unary) {
                origin(unary.operand(), environment);
                return false;
            }
            if (expression instanceof InstanceOfExpression typeTest) {
                origin(typeTest.operand(), environment);
            }
            return false;
        }

        private boolean isKnownBorrowingStreamRead(CallExpression call) {
            if (!call.methodName().equals("read") || call.receiver().isEmpty()) {
                return false;
            }
            Expression receiver = call.receiver().orElseThrow();
            if (!(receiver instanceof FieldAccessExpression access)
                    || !(access.receiver() instanceof ThisExpression)) {
                return false;
            }
            FieldSymbol field = owner.declaredFields().get(access.fieldName());
            return field != null && field.type().isNominalReference()
                    && field.type().referenceName().equals("ironwood.io.InputStream");
        }

        private void assign(Expression target, Expression value, Map<String, Boolean> environment,
                            boolean fresh) {
            if (target instanceof ArrayAccessExpression access
                    && origin(access.array(), environment)
                    && (containsReentrantExpression(access.index())
                    || containsReentrantExpression(value))) {
                reject();
            }
            assignKnownOrigin(target, assignmentOrigin(target, value, environment), environment, fresh);
        }

        private boolean assignmentOrigin(Expression target, Expression value,
                                         Map<String, Boolean> environment) {
            FieldSymbol previousTarget = constructionTarget;
            String sibling = privateSiblingFieldName(target);
            constructionTarget = sibling == null ? null : owner.declaredFields().get(sibling);
            boolean valueOrigin = origin(value, environment);
            constructionTarget = previousTarget;
            return valueOrigin;
        }

        private boolean isContainedEntryBuilderBorrow(CallableSymbol constructor, int index) {
            if (constructor == null || currentCallable == null || !currentCallable.isConstructor()
                    || !DataStructureSemantics.isEntryBuilder(candidate)
                    || constructionTarget == null
                    || !DataStructureSemantics.isEntryPool(constructionTarget)
                    || !constructor.ownerType().equals(constructionTarget.type().referenceName())
                    || !DataStructureSemantics.hasOrderedCleanup(owner)) {
                return false;
            }
            EscapeSummaryAnalyzer.EscapeSummary summary = escapeSummaries.summary(constructor);
            FieldSymbol retained = escapeSummaries.retainedParameterField(constructor, index);
            return summary.parameterRetainedByReceiverOnly(index) && retained != null
                    && isEncapsulated(retained)
                    && new Checker(owner, constructionTarget, true, true).isOwned();
        }

        private void assignKnownOrigin(Expression target, boolean valueOrigin,
                                       Map<String, Boolean> environment, boolean fresh) {
            if (target instanceof NameExpression name && environment.containsKey(name.name())) {
                environment.put(name.name(), valueOrigin);
                return;
            }
            if (isCandidateField(target)) {
                if (requireFreshWrites && !fresh) {
                    reject();
                } else {
                    environment.replaceAll((name, attached) -> false);
                }
                return;
            }
            String sibling = privateSiblingFieldName(target);
            if (valueOrigin && sibling != null) {
                reject("allocation escapes through field '" + sibling + "'");
                return;
            }
            if (valueOrigin) {
                reject();
            }
            if (target instanceof FieldAccessExpression access) {
                if (access.fieldName().equals(candidate.declaration().name())) {
                    reject();
                }
                origin(access.receiver(), environment);
            } else if (target instanceof ArrayAccessExpression access) {
                origin(access.array(), environment);
                origin(access.index(), environment);
            } else {
                origin(target, environment);
            }
        }

        private boolean isCandidateField(Expression expression) {
            if (expression instanceof NameExpression name) {
                return !staticFunction && name.name().equals(candidate.declaration().name());
            }
            return expression instanceof FieldAccessExpression access
                    && access.fieldName().equals(candidate.declaration().name())
                    && access.receiver() instanceof ThisExpression;
        }

        private boolean isBorrowedReturnExpression(Expression expression) {
            if (isCandidateField(expression)) {
                return true;
            }
            if (expression instanceof CastExpression cast) {
                return isBorrowedReturnExpression(cast.operand());
            }
            if (!(expression instanceof CallExpression call)
                    || call.receiver().isEmpty()
                    || !isCandidateField(call.receiver().orElseThrow())) {
                return false;
            }
            CallableSymbol target = resolveAttachedCall(call);
            if (target == null) {
                return false;
            }
            EscapeSummaryAnalyzer.EscapeSummary summary = escapeSummaries.summary(target);
            return !summary.mayReturnNonOrigin()
                    && summary.borrowedReturnedOrigins().isEmpty()
                    && summary.returnedOrigins().size() == 1
                    && summary.returnedOrigins().iterator().next().kind()
                    == ReturnOrigin.Kind.THIS;
        }

        private String privateSiblingFieldName(Expression expression) {
            String name;
            if (expression instanceof NameExpression fieldName) {
                name = fieldName.name();
            } else if (expression instanceof FieldAccessExpression access
                    && access.receiver() instanceof ThisExpression) {
                name = access.fieldName();
            } else {
                return null;
            }
            FieldSymbol field = owner.declaredFields().get(name);
            return field != null && field != candidate && !field.isStatic()
                    && field.accessModifier() == ironwood.compiler.ast.AccessModifier.PRIVATE
                    ? name : null;
        }

        private void reject() {
            owned = false;
        }

        private void reject(String reason) {
            reject();
            if (rejectionReason == null) {
                rejectionReason = reason;
            }
        }

        private boolean isFreshValue(Expression expression) {
            return expression instanceof ArrayCreationExpression
                    || expression instanceof ArrayInitializerExpression
                    || expression instanceof NewExpression
                    || expression instanceof CallExpression call
                    && isFreshStorageHelper(call)
                    || expression instanceof NullLiteralExpression;
        }

        private boolean isFreshStorageHelper(CallExpression call) {
            CallableSymbol target = resolveStaticCall(call, Map.of());
            if (target == null) { return false; }
            if (target.ownerType().equals("ironwood.nio.ByteBuffer")
                    && target.sourceName().equals("allocate")) {
                // Use the source-derived factory proof, not a fresh-result promise
                // based on the name alone. Cached or published buffers must fail.
                return escapeSummaries.summary(target).returnsOwnedFresh();
            }
            return target.ownerType().equals("ironwood.nio.file.Paths")
                    && AllocationResultSemantics.returnsOwnedFresh(target)
                    && target.returnType().equals(
                    IrType.reference("ironwood.lang.String"));
        }

        private CallableSymbol resolveStaticCall(CallExpression call,
                                                 Map<String, Boolean> environment) {
            if (call.receiver().isEmpty()) {
                return null;
            }
            String receiverName = qualifiedName(call.receiver().orElseThrow());
            if (receiverName == null || environment.containsKey(receiverName)) {
                return null;
            }
            TypeSymbol receiverType = hierarchy.resolveType(receiverName, owner)
                    .type().orElse(null);
            if (receiverType == null) {
                return null;
            }
            java.util.List<CallableSymbol> candidates = receiverType
                    .declaredMethodsNamed(call.methodName()).stream()
                    .filter(CallableSymbol::isStatic)
                    .filter(method -> method.parameterTypes().size() == call.arguments().size())
                    .toList();
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }

        private CallableSymbol resolveAttachedCall(CallExpression call) {
            if (!candidate.type().isNominalReference()) {
                return null;
            }
            TypeSymbol receiverType = hierarchy.type(candidate.type().referenceName())
                    .orElse(null);
            for (TypeSymbol current = receiverType; current != null;
                 current = current.superclass().orElse(null)) {
                java.util.List<CallableSymbol> candidates = current
                        .declaredMethodsNamed(call.methodName()).stream()
                        .filter(method -> !method.isStatic())
                        .filter(method -> method.parameterTypes().size()
                                == call.arguments().size())
                        .toList();
                if (!candidates.isEmpty()) {
                    return candidates.size() == 1
                            && directlyBound(candidates.getFirst())
                            ? candidates.getFirst() : null;
                }
            }
            return null;
        }

        private CallableSymbol resolveConstructor(NewExpression allocation,
                                                   Map<String, Boolean> environment) {
            TypeSymbol allocated = hierarchy.resolveType(allocation.className(), owner)
                    .type().orElse(null);
            if (allocated == null) {
                return null;
            }
            java.util.List<CallableSymbol> candidates = allocated.constructors().stream()
                    .filter(candidate -> candidate.parameters().size()
                            == allocation.arguments().size())
                    .filter(constructor -> {
                        for (int index = 0; index < allocation.arguments().size(); index++) {
                            Expression argument = allocation.arguments().get(index);
                            if (isCandidateField(argument)
                                    && !(argument instanceof NameExpression name
                                    && environment.containsKey(name.name()))
                                    && !constructor.parameterTypes().get(index).isReference()) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .toList();
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }

        private boolean directlyBound(CallableSymbol method) {
            TypeSymbol declaringType = types.get(method.ownerType());
            return method.ownerType().equals("ironwood.pool.ObjectBuilder")
                    && method.sourceName().equals("newInstance") && method.parameterTypes().isEmpty()
                    || method.isFinal()
                    || method.accessModifier()
                    == ironwood.compiler.ast.AccessModifier.PRIVATE
                    || declaringType != null && !declaringType.isInterface()
                    && (declaringType.isFinal()
                    || !hasClosedWorldOverride(declaringType, method));
        }

        private boolean hasClosedWorldOverride(TypeSymbol declaringType,
                                               CallableSymbol method) {
            for (TypeSymbol type : types.values()) {
                if (type == declaringType || type.isInterface()
                        || !isSubtype(type, declaringType)) {
                    continue;
                }
                boolean overrides = type.declaredMethodsNamed(method.sourceName()).stream()
                        .anyMatch(other -> !other.isStatic()
                                && other.erasedSignatureKey().equals(
                                method.erasedSignatureKey()));
                if (overrides) {
                    return true;
                }
            }
            return false;
        }

        private boolean isSubtype(TypeSymbol actual, TypeSymbol expected) {
            for (TypeSymbol current = actual; current != null;
                 current = current.superclass().orElse(null)) {
                if (current.name().equals(expected.name())) {
                    return true;
                }
            }
            return false;
        }

        private boolean isSystemArrayCopy(CallExpression call,
                                          Map<String, Boolean> environment) {
            if (!call.methodName().equals("arraycopy") || call.receiver().isEmpty()) {
                return false;
            }
            if (call.arguments().stream().anyMatch(this::containsReentrantExpression)) {
                return false;
            }
            String name = qualifiedName(call.receiver().orElseThrow());
            if (name == null || environment.containsKey(name)) {
                return false;
            }
            TypeResolver.Resolution resolution = hierarchy.resolveType(name, owner);
            return resolution.type().map(TypeSymbol::name)
                    .filter("ironwood.lang.System"::equals).isPresent();
        }

        private boolean containsReentrantExpression(Expression expression) {
            if (expression instanceof CallExpression || expression instanceof NewExpression) {
                return true;
            }
            if (expression instanceof SwitchExpression) {
                return true;
            }
            if (expression instanceof FieldAccessExpression access) {
                return containsReentrantExpression(access.receiver());
            }
            if (expression instanceof ArrayAccessExpression access) {
                return containsReentrantExpression(access.array())
                        || containsReentrantExpression(access.index());
            }
            if (expression instanceof ArrayCreationExpression creation) {
                return creation.length().map(this::containsReentrantExpression).orElse(false)
                        || creation.initializer()
                        .map(this::containsReentrantExpression).orElse(false);
            }
            if (expression instanceof ArrayInitializerExpression initializer) {
                return initializer.elements().stream()
                        .anyMatch(this::containsReentrantExpression);
            }
            if (expression instanceof BinaryExpression binary) {
                return containsReentrantExpression(binary.left())
                        || containsReentrantExpression(binary.right());
            }
            if (expression instanceof AssignmentExpression assignment) {
                return containsReentrantExpression(assignment.target())
                        || containsReentrantExpression(assignment.value());
            }
            if (expression instanceof ConditionalExpression conditional) {
                return containsReentrantExpression(conditional.condition())
                        || containsReentrantExpression(conditional.whenTrue())
                        || containsReentrantExpression(conditional.whenFalse());
            }
            if (expression instanceof CastExpression cast) {
                return containsReentrantExpression(cast.operand());
            }
            if (expression instanceof UpdateExpression update) {
                return containsReentrantExpression(update.target());
            }
            if (expression instanceof UnaryExpression unary) {
                return containsReentrantExpression(unary.operand());
            }
            return expression instanceof InstanceOfExpression typeTest
                    && containsReentrantExpression(typeTest.operand());
        }

        private void scanSwitchRuleBody(ironwood.compiler.ast.SwitchRuleBody body,
                                        Map<String, Boolean> environment) {
            if (body instanceof SwitchRuleExpression result) {
                origin(result.expression(), environment);
            } else if (body instanceof SwitchRuleBlock block) {
                scanBlock(block.block(), environment, true);
            } else if (body instanceof SwitchRuleThrow thrown) {
                scanStatement(thrown.statement(), environment);
            }
        }

        private String qualifiedName(Expression expression) {
            if (expression instanceof NameExpression name) {
                return name.name();
            }
            if (expression instanceof FieldAccessExpression access) {
                String receiver = qualifiedName(access.receiver());
                return receiver == null ? null : receiver + "." + access.fieldName();
            }
            return null;
        }

        private Map<String, Boolean> copy(Map<String, Boolean> environment) {
            return new LinkedHashMap<>(environment);
        }

        private void merge(Map<String, Boolean> target, Map<String, Boolean> first,
                           Map<String, Boolean> second) {
            for (String name : Set.copyOf(target.keySet())) {
                target.put(name, first.getOrDefault(name, false)
                        || second.getOrDefault(name, false));
            }
        }
    }
}
