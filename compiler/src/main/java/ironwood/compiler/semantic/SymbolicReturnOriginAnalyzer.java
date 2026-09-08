// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.ArrayAccessExpression;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.AssignmentExpression;
import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EmptyStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.InstanceOfExpression;
import ironwood.compiler.ast.InterfaceSuperExpression;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.LabeledStatement;
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
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchRuleThrow;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ast.ThisExpression;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;
import ironwood.compiler.ir.IrType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Computes return provenance independently of the established escape decisions. */
final class SymbolicReturnOriginAnalyzer {
    private final Map<String, TypeSymbol> types;
    private final TypeResolver resolver;
    private final OwnedArrayFieldAnalyzer ownedFields;
    private final EscapeSummaryAnalyzer escapeSummaries;
    private final Map<String, CallableSymbol> callables = new LinkedHashMap<>();
    private final Map<String, ReturnSummary> summaries = new LinkedHashMap<>();
    private TypeSymbol owner;
    private CallableSymbol callable;
    private Set<ReturnOrigin> nonReturnEscaping;
    private Set<Expression> escapingFreshOrigins;

    SymbolicReturnOriginAnalyzer(Map<String, TypeSymbol> types) {
        this(types, null, null);
    }

    SymbolicReturnOriginAnalyzer(Map<String, TypeSymbol> types,
                                 OwnedArrayFieldAnalyzer ownedFields) {
        this(types, ownedFields, null);
    }

    SymbolicReturnOriginAnalyzer(Map<String, TypeSymbol> types,
                                 OwnedArrayFieldAnalyzer ownedFields,
                                 EscapeSummaryAnalyzer escapeSummaries) {
        this.escapeSummaries = escapeSummaries;
        this.types = types;
        this.ownedFields = ownedFields;
        resolver = new TypeResolver(types);
        for (TypeSymbol type : types.values()) {
            type.constructors().forEach(this::register);
            type.declaredMethods().values().forEach(this::register);
        }
    }

    Map<String, ReturnSummary> analyze() {
        boolean changed;
        do {
            changed = false;
            for (CallableSymbol candidate : callables.values()) {
                ReturnSummary discovered = analyze(candidate);
                ReturnSummary previous = summaries.get(candidate.linkageName());
                ReturnSummary merged = previous.merge(discovered);
                if (!merged.equals(previous)) {
                    summaries.put(candidate.linkageName(), merged);
                    changed = true;
                }
            }
        } while (changed);
        return Collections.unmodifiableMap(new LinkedHashMap<>(summaries));
    }

    private void register(CallableSymbol candidate) {
        callables.put(candidate.linkageName(), candidate);
        summaries.put(candidate.linkageName(), ReturnSummary.empty());
    }

    private ReturnSummary analyze(CallableSymbol candidate) {
        owner = types.get(candidate.ownerType());
        callable = candidate;
        nonReturnEscaping = new LinkedHashSet<>();
        escapingFreshOrigins = new LinkedHashSet<>();
        ReturnSummary poolContract = PoolSemantics.symbolic(candidate);
        if (poolContract != null) { return poolContract; }
        if (AllocationResultSemantics.returnsOwnedFresh(candidate)) {
            return new ReturnSummary(Set.of(), Set.of(), Set.of(),
                    false, true, false, false);
        }
        Map<String, SymbolicValue> environment = new LinkedHashMap<>();
        for (int index = 0; index < candidate.parameters().size(); index++) {
            IrType type = index < candidate.parameterTypes().size()
                    ? candidate.parameterTypes().get(index) : null;
            environment.put(candidate.parameters().get(index).name(),
                    type != null && type.isReference()
                            ? SymbolicValue.exact(ReturnOrigin.parameter(index), type)
                            : SymbolicValue.unknown(type));
        }
        ReturnAccumulator returned = new ReturnAccumulator();
        if (candidate.body().isPresent()) {
            scanBlock(candidate.body().orElseThrow(), environment, returned, false);
        } else {
            publishEnvironment(environment);
            if (!candidate.isStatic()) {
                publish(SymbolicValue.exact(ReturnOrigin.thisOrigin(), owner.selfType()));
            }
            returned.mayReturnNonOrigin |= candidate.returnType().isReference();
        }
        boolean returnedFreshEscapes = returned.freshOrigins.stream()
                .anyMatch(escapingFreshOrigins::contains);
        String borrowedResultType = DataStructureSemantics.borrowedResultType(candidate);
        if (borrowedResultType != null) {
            // Entry accessors and copied-key getters lend container-owned storage.
            // Retain ordinary parameter effects, including inserted payloads.
            return new ReturnSummary(Set.of(), Set.of(new BorrowedReturnOrigin(
                    ReturnOrigin.thisOrigin(), borrowedResultType)),
                    nonReturnEscaping, false, false, true, false);
        }
        return new ReturnSummary(returned.origins, returned.borrowedOrigins,
                nonReturnEscaping,
                returned.mayReturnNonOrigin, !returned.freshOrigins.isEmpty(),
                returned.mayReturnNull, returnedFreshEscapes);
    }

    private void scanBlock(Block block, Map<String, SymbolicValue> environment,
                           ReturnAccumulator returned, boolean scoped) {
        Set<String> existing = Set.copyOf(environment.keySet());
        for (Statement statement : block.statements()) {
            scanStatement(statement, environment, returned);
        }
        if (scoped) {
            environment.keySet().removeIf(name -> !existing.contains(name));
        }
    }

    private void scanStatement(Statement statement, Map<String, SymbolicValue> environment,
                               ReturnAccumulator returned) {
        if (statement instanceof Block block) {
            scanBlock(block, environment, returned, true);
        } else if (statement instanceof LocalVariableDeclaration local) {
            SymbolicValue initializer = value(local.initializer(), environment);
            environment.put(local.name(), initializer.withType(sourceType(local.type())));
        } else if (statement instanceof AssignmentStatement assignment) {
            SymbolicValue assigned = value(assignment.value(), environment);
            assign(assignment.target(), assigned, environment);
        } else if (statement instanceof ExpressionStatement expression) {
            value(expression.expression(), environment);
        } else if (statement instanceof FreeStatement free) {
            publish(value(free.value(), environment));
        } else if (statement instanceof ReturnStatement returnStatement) {
            returnStatement.value().ifPresent(expression -> {
                SymbolicValue returnedValue = value(expression, environment);
                returned.origins.addAll(returnedValue.origins());
                returned.borrowedOrigins.addAll(returnedValue.borrowedOrigins());
                returned.freshOrigins.addAll(returnedValue.freshOrigins());
                returned.mayReturnNonOrigin |= returnedValue.mayBeNonOrigin();
                returned.mayReturnNull |= returnedValue.mayBeNull();
            });
        } else if (statement instanceof ThrowStatement thrown) {
            publish(value(thrown.value(), environment));
        } else if (statement instanceof YieldStatement yielded) {
            value(yielded.value(), environment);
        } else if (statement instanceof SuperConstructorInvocation invocation) {
            invocation.enclosingInstance().ifPresent(expression ->
                    publish(value(expression, environment)));
            invocation.arguments().forEach(expression ->
                    publish(value(expression, environment)));
        } else if (statement instanceof ThisConstructorInvocation invocation) {
            invocation.arguments().forEach(expression ->
                    publish(value(expression, environment)));
        } else if (statement instanceof IfStatement conditional) {
            value(conditional.condition(), environment);
            Map<String, SymbolicValue> thenEnvironment = copy(environment);
            scanScoped(conditional.thenBranch(), thenEnvironment, returned);
            Map<String, SymbolicValue> elseEnvironment = copy(environment);
            conditional.elseBranch().ifPresent(branch ->
                    scanScoped(branch, elseEnvironment, returned));
            mergeExisting(environment, thenEnvironment, elseEnvironment);
        } else if (statement instanceof WhileStatement loop) {
            value(loop.condition(), environment);
            Map<String, SymbolicValue> bodyEnvironment = copy(environment);
            scanScoped(loop.body(), bodyEnvironment, returned);
            mergeExisting(environment, environment, bodyEnvironment);
        } else if (statement instanceof DoWhileStatement loop) {
            Map<String, SymbolicValue> bodyEnvironment = copy(environment);
            scanScoped(loop.body(), bodyEnvironment, returned);
            value(loop.condition(), bodyEnvironment);
            mergeExisting(environment, environment, bodyEnvironment);
        } else if (statement instanceof ForStatement loop) {
            Map<String, SymbolicValue> loopEnvironment = copy(environment);
            loop.initializer().ifPresent(initializer ->
                    scanStatement(initializer, loopEnvironment, returned));
            loop.condition().ifPresent(condition -> value(condition, loopEnvironment));
            Map<String, SymbolicValue> bodyEnvironment = copy(loopEnvironment);
            scanScoped(loop.body(), bodyEnvironment, returned);
            loop.updates().forEach(update -> value(update, bodyEnvironment));
            mergeExisting(environment, environment, bodyEnvironment);
        } else if (statement instanceof EnhancedForStatement loop) {
            SymbolicValue source = value(loop.iterable(), environment);
            Map<String, SymbolicValue> bodyEnvironment = copy(environment);
            IrType variableType = sourceType(loop.variableType());
            bodyEnvironment.put(loop.variableName(), SymbolicValue.unknown(variableType));
            scanScoped(loop.body(), bodyEnvironment, returned);
            mergeExisting(environment, environment, bodyEnvironment);
        } else if (statement instanceof LabeledStatement labeled) {
            scanScoped(labeled.body(), environment, returned);
        } else if (statement instanceof EmptyStatement) {
            // No values or effects.
        } else if (statement instanceof SwitchStatement switched) {
            value(switched.selector(), environment);
            Map<String, SymbolicValue> merged = copy(environment);
            Map<String, SymbolicValue> fallthrough = copy(environment);
            for (var group : switched.groups()) {
                Map<String, SymbolicValue> groupEnvironment = copy(environment);
                mergeExisting(groupEnvironment, groupEnvironment, fallthrough);
                for (Statement child : group.statements()) {
                    scanStatement(child, groupEnvironment, returned);
                }
                mergeExisting(merged, merged, groupEnvironment);
                fallthrough = groupEnvironment;
            }
            environment.clear();
            environment.putAll(merged);
        } else if (statement instanceof ModernSwitchStatement switched) {
            value(switched.selector(), environment);
            Map<String, SymbolicValue> merged = copy(environment);
            for (var rule : switched.rules()) {
                Map<String, SymbolicValue> branch = copy(environment);
                scanSwitchRuleBody(rule.body(), branch, returned);
                mergeExisting(merged, merged, branch);
            }
            environment.clear();
            environment.putAll(merged);
        } else if (statement instanceof TryStatement guarded) {
            Map<String, SymbolicValue> merged = copy(environment);
            Map<String, SymbolicValue> tryEnvironment = copy(environment);
            scanBlock(guarded.body(), tryEnvironment, returned, true);
            mergeExisting(merged, merged, tryEnvironment);
            guarded.catches().forEach(caught -> {
                Map<String, SymbolicValue> catchEnvironment = copy(environment);
                catchEnvironment.put(caught.variableName(), SymbolicValue.unknown(null));
                scanBlock(caught.body(), catchEnvironment, returned, true);
                mergeExisting(merged, merged, catchEnvironment);
            });
            guarded.finallyBlock().ifPresent(cleanup ->
                    scanBlock(cleanup, merged, returned, true));
            environment.clear();
            environment.putAll(merged);
        }
    }

    private void scanScoped(Statement statement, Map<String, SymbolicValue> environment,
                            ReturnAccumulator returned) {
        Set<String> existing = Set.copyOf(environment.keySet());
        scanStatement(statement, environment, returned);
        environment.keySet().removeIf(name -> !existing.contains(name));
    }

    private SymbolicValue value(Expression expression,
                                Map<String, SymbolicValue> environment) {
        if (expression instanceof SwitchExpression switched) {
            value(switched.selector(), environment);
            Map<String, SymbolicValue> merged = copy(environment);
            SymbolicValue result = null;
            if (switched.arrowRules()) {
                for (var rule : switched.rules()) {
                    Map<String, SymbolicValue> branch = copy(environment);
                    if (rule.body() instanceof SwitchRuleExpression expressionResult) {
                        SymbolicValue branchResult = value(expressionResult.expression(), branch);
                        result = result == null ? branchResult
                                : SymbolicValue.merge(result, branchResult);
                    } else {
                        scanSwitchRuleBody(rule.body(), branch, new ReturnAccumulator());
                    }
                    mergeExisting(merged, merged, branch);
                }
            } else {
                Map<String, SymbolicValue> fallthrough = copy(environment);
                for (var group : switched.groups()) {
                    Map<String, SymbolicValue> branch = copy(environment);
                    mergeExisting(branch, branch, fallthrough);
                    for (Statement child : group.statements()) {
                        scanStatement(child, branch, new ReturnAccumulator());
                    }
                    mergeExisting(merged, merged, branch);
                    fallthrough = branch;
                }
            }
            environment.clear();
            environment.putAll(merged);
            return result == null ? SymbolicValue.unknown(null) : result;
        }
        if (expression instanceof NameExpression name) {
            return environment.getOrDefault(name.name(), SymbolicValue.unknown(null));
        }
        if (expression instanceof NullLiteralExpression) {
            return SymbolicValue.nullValue();
        }
        if (expression instanceof StringLiteralExpression) {
            // A literal is borrowed immortal text, but its type lets calls such
            // as literal.substring(...) retain their source-derived result proof.
            return SymbolicValue.unknown(IrType.reference("ironwood.lang.String"));
        }
        if (expression instanceof ThisExpression || expression instanceof QualifiedThisExpression) {
            return callable.isStatic() ? SymbolicValue.unknown(null)
                    : SymbolicValue.exact(ReturnOrigin.thisOrigin(), owner.selfType());
        }
        if (expression instanceof SuperExpression) {
            return callable.isStatic() ? SymbolicValue.unknown(null)
                    : SymbolicValue.exact(ReturnOrigin.thisOrigin(),
                    owner.superclass().map(TypeSymbol::selfType).orElse(null));
        }
        if (expression instanceof InterfaceSuperExpression) {
            return callable.isStatic() ? SymbolicValue.unknown(null)
                    : SymbolicValue.exact(ReturnOrigin.thisOrigin(), owner.selfType());
        }
        if (expression instanceof FieldAccessExpression access) {
            value(access.receiver(), environment);
            return SymbolicValue.unknown(null);
        }
        if (expression instanceof ArrayAccessExpression access) {
            SymbolicValue array = value(access.array(), environment);
            value(access.index(), environment);
            IrType elementType = array.type() != null && array.type().isArray()
                    ? array.type().elementType() : null;
            Set<ReturnOrigin> elements = elementsOf(array.origins());
            return new SymbolicValue(elements, Set.of(), Set.of(), elementType,
                    array.mayBeNonOrigin() || elements.isEmpty(), false);
        }
        if (expression instanceof ArrayCreationExpression creation) {
            creation.length().ifPresent(length -> value(length, environment));
            creation.initializer().ifPresent(initializer -> value(initializer, environment));
            return SymbolicValue.fresh(creation, sourceType(creation.elementType()) == null
                    ? null : IrType.array(sourceType(creation.elementType())));
        }
        if (expression instanceof ArrayInitializerExpression initializer) {
            initializer.elements().forEach(element -> publish(value(element, environment)));
            return SymbolicValue.fresh(initializer, null);
        }
        if (expression instanceof NewExpression allocation) {
            allocation.enclosingInstance().ifPresent(enclosing ->
                    publish(value(enclosing, environment)));
            if (allocation.enclosingInstance().isEmpty() && !callable.isStatic()
                    && capturesImplicitEnclosingInstance(allocation)) {
                publish(SymbolicValue.exact(ReturnOrigin.thisOrigin(), owner.selfType()));
            }
            IrType allocatedType = sourceType(allocation.classType());
            boolean copiesStorage = allocatedType != null
                    && (allocatedType.equals(IrType.reference("ironwood.lang.String"))
                    && (allocation.arguments().size() == 1
                    || allocation.arguments().size() == 3)
                    || allocatedType.equals(IrType.reference("ironwood.nio.file.UnixPath"))
                    && (allocation.arguments().size() == 1 || allocation.arguments().size() == 3));
            allocation.arguments().forEach(argument -> {
                SymbolicValue argumentValue = value(argument, environment);
                if (!copiesStorage) {
                    publish(argumentValue);
                }
            });
            return SymbolicValue.fresh(allocation, allocatedType);
        }
        if (expression instanceof QualifiedSuperConstructorExpression invocation) {
            publish(value(invocation.enclosingInstance(), environment));
            invocation.arguments().forEach(argument ->
                    publish(value(argument, environment)));
            return SymbolicValue.unknown(null);
        }
        if (expression instanceof CallExpression call) {
            return callValue(call, environment);
        }
        if (expression instanceof BinaryExpression binary) {
            value(binary.left(), environment);
            Map<String, SymbolicValue> withoutRight = copy(environment);
            Map<String, SymbolicValue> withRight = copy(environment);
            value(binary.right(), withRight);
            mergeExisting(environment, withoutRight, withRight);
            return SymbolicValue.unknown(null);
        }
        if (expression instanceof AssignmentExpression assignment) {
            SymbolicValue assigned = value(assignment.value(), environment);
            if (assignment.operator() == ironwood.compiler.ast.AssignmentOperator.ASSIGN) {
                assign(assignment.target(), assigned, environment);
                return assigned;
            }
            value(assignment.target(), environment);
            return SymbolicValue.unknown(null);
        }
        if (expression instanceof ConditionalExpression conditional) {
            value(conditional.condition(), environment);
            Map<String, SymbolicValue> trueEnvironment = copy(environment);
            SymbolicValue whenTrue = value(conditional.whenTrue(), trueEnvironment);
            Map<String, SymbolicValue> falseEnvironment = copy(environment);
            SymbolicValue whenFalse = value(conditional.whenFalse(), falseEnvironment);
            mergeExisting(environment, trueEnvironment, falseEnvironment);
            return SymbolicValue.merge(whenTrue, whenFalse);
        }
        if (expression instanceof CastExpression cast) {
            SymbolicValue operand = value(cast.operand(), environment);
            return operand.withType(sourceType(cast.targetType()));
        }
        if (expression instanceof ironwood.compiler.ast.UpdateExpression update) {
            value(update.target(), environment);
            return SymbolicValue.unknown(null);
        }
        if (expression instanceof ironwood.compiler.ast.UnaryExpression unary) {
            value(unary.operand(), environment);
            return SymbolicValue.unknown(null);
        }
        if (expression instanceof InstanceOfExpression typeTest) {
            value(typeTest.operand(), environment);
        }
        return SymbolicValue.unknown(null);
    }

    private void scanSwitchRuleBody(ironwood.compiler.ast.SwitchRuleBody body,
                                    Map<String, SymbolicValue> environment,
                                    ReturnAccumulator returned) {
        if (body instanceof SwitchRuleExpression result) {
            value(result.expression(), environment);
        } else if (body instanceof SwitchRuleBlock block) {
            scanBlock(block.block(), environment, returned, true);
        } else if (body instanceof SwitchRuleThrow thrown) {
            scanStatement(thrown.statement(), environment, returned);
        }
    }

    private SymbolicValue callValue(CallExpression call,
                                    Map<String, SymbolicValue> environment) {
        Receiver receiver = receiver(call, environment);
        List<SymbolicValue> arguments = call.arguments().stream()
                .map(argument -> value(argument, environment)).toList();
        CallableSymbol target = resolve(call, receiver, arguments.size());
        if (escapeSummaries != null && escapeSummaries.isNonRetainingPrimitiveCall(
                owner, callable, call, environment.keySet())) {
            return SymbolicValue.unknown(target == null ? null : target.returnType());
        }
        if (target == null) {
            if (isKnownBorrowingStringBuilderAppend(call, receiver)) {
                return receiver.value();
            }
            if (isKnownNonEscapingStringCall(call, receiver)) {
                return SymbolicValue.unknown(null);
            }
            publish(receiver.value());
            arguments.forEach(this::publish);
            return SymbolicValue.unknown(null);
        }
        ReturnSummary targetSummary = summaries.getOrDefault(
                target.linkageName(), ReturnSummary.empty());
        for (ReturnOrigin escaping : targetSummary.nonReturnEscapingOrigins()) {
            publish(map(escaping, receiver.value(), arguments));
        }
        FieldSymbol directBorrow = ownedFields == null
                ? null : ownedFields.borrowedReturnField(target);
        if (directBorrow != null && !target.isStatic()
                && directBorrow.type().isNominalReference()) {
            LinkedHashSet<BorrowedReturnOrigin> directResult = new LinkedHashSet<>();
            String helperType = directBorrow.type().referenceName();
            for (ReturnOrigin dependency : dependencyOrigins(receiver.value())) {
                directResult.add(new BorrowedReturnOrigin(dependency, helperType));
            }
            return new SymbolicValue(Set.of(), directResult, Set.of(), target.returnType(),
                    receiver.value().mayBeNonOrigin(), false);
        }
        LinkedHashSet<ReturnOrigin> result = new LinkedHashSet<>();
        LinkedHashSet<BorrowedReturnOrigin> borrowedResult = new LinkedHashSet<>();
        boolean mayBeNonOrigin = targetSummary.mayReturnNonOrigin();
        for (ReturnOrigin origin : targetSummary.returnedOrigins()) {
            SymbolicValue mapped = map(origin, receiver.value(), arguments);
            result.addAll(mapped.origins());
            borrowedResult.addAll(mapped.borrowedOrigins());
            mayBeNonOrigin |= mapped.mayBeNonOrigin();
        }
        for (BorrowedReturnOrigin borrowed : targetSummary.borrowedReturnedOrigins()) {
            SymbolicValue mapped = map(borrowed.ownerOrigin(), receiver.value(), arguments);
            for (ReturnOrigin dependency : dependencyOrigins(mapped)) {
                borrowedResult.add(new BorrowedReturnOrigin(
                        dependency, borrowed.helperType()));
            }
            mayBeNonOrigin |= mapped.mayBeNonOrigin();
        }
        Set<Expression> freshOrigins = targetSummary.mayReturnFresh()
                ? Set.of(call) : Set.of();
        if (targetSummary.freshEscapes()) {
            escapingFreshOrigins.add(call);
        }
        return new SymbolicValue(result, borrowedResult, freshOrigins,
                target.returnType(), mayBeNonOrigin, targetSummary.mayReturnNull());
    }

    private static boolean isKnownNonEscapingStringCall(CallExpression call,
                                                         Receiver receiver) {
        if (receiver.type() == null
                || !receiver.type().name().equals("ironwood.lang.String")) {
            return false;
        }
        return switch (call.methodName()) {
            case "length", "byteLength", "isEmpty", "charAt", "uncheckedCharAt",
                    "contains", "indexOf", "lastIndexOf", "startsWith", "endsWith",
                    "compareTo", "equals", "contentEquals", "hashCode", "contentHashCode",
                    "getChars" -> true;
            default -> false;
        };
    }

    private static boolean isKnownBorrowingStringBuilderAppend(
            CallExpression call, Receiver receiver) {
        return receiver.type() != null
                && receiver.type().name().equals("ironwood.lang.StringBuilder")
                && call.methodName().equals("append") && call.arguments().size() == 1;
    }

    private Receiver receiver(CallExpression call, Map<String, SymbolicValue> environment) {
        if (call.receiver().isEmpty()) {
            SymbolicValue implicit = callable.isStatic() ? SymbolicValue.unknown(null)
                    : SymbolicValue.exact(ReturnOrigin.thisOrigin(), owner.selfType());
            return new Receiver(owner, implicit, ReceiverKind.IMPLICIT);
        }
        Expression expression = call.receiver().orElseThrow();
        if (expression instanceof NameExpression name && !environment.containsKey(name.name())) {
            TypeSymbol staticType = resolver.resolve(name.name(), owner).type().orElse(null);
            if (staticType != null) {
                return new Receiver(staticType, SymbolicValue.unknown(null), ReceiverKind.STATIC_TYPE);
            }
        }
        SymbolicValue value = value(expression, environment);
        if (expression instanceof SuperExpression) {
            return new Receiver(owner.superclass().orElse(null), value, ReceiverKind.SUPER);
        }
        if (expression instanceof InterfaceSuperExpression interfaceSuper) {
            TypeSymbol interfaceType = resolver.resolve(interfaceSuper.interfaceName(), owner)
                    .type().orElse(null);
            return new Receiver(interfaceType, value, ReceiverKind.SUPER);
        }
        return new Receiver(symbol(value.type()), value, ReceiverKind.INSTANCE);
    }

    private CallableSymbol resolve(CallExpression call, Receiver receiver, int argumentCount) {
        if (receiver.type() == null) {
            return null;
        }
        List<CallableSymbol> candidates = methodsNamed(receiver.type(), call.methodName()).stream()
                .filter(method -> method.parameterTypes().size() == argumentCount)
                .filter(method -> switch (receiver.kind()) {
                    case STATIC_TYPE -> method.isStatic();
                    case INSTANCE, SUPER -> !method.isStatic();
                    case IMPLICIT -> callable.isStatic() ? method.isStatic() : true;
                })
                .filter(method -> directlyBound(method, receiver.kind())).toList();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private List<CallableSymbol> methodsNamed(TypeSymbol start, String name) {
        List<CallableSymbol> result = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        for (TypeSymbol current = start; current != null;
             current = current.superclass().orElse(null)) {
            for (CallableSymbol method : current.declaredMethodsNamed(name)) {
                if (signatures.add(method.erasedSignatureKey())) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    private boolean directlyBound(CallableSymbol method, ReceiverKind kind) {
        TypeSymbol declaringType = types.get(method.ownerType());
        return kind == ReceiverKind.SUPER || method.isStatic() || method.isFinal()
                || method.accessModifier() == ironwood.compiler.ast.AccessModifier.PRIVATE
                || declaringType != null && !declaringType.isInterface()
                && (declaringType.isFinal()
                || !hasClosedWorldOverride(declaringType, method));
    }

    private boolean hasClosedWorldOverride(TypeSymbol declaringType, CallableSymbol method) {
        for (TypeSymbol candidate : types.values()) {
            if (candidate == declaringType || candidate.isInterface()
                    || !isSubtype(candidate, declaringType)) {
                continue;
            }
            boolean overrides = candidate.declaredMethodsNamed(method.sourceName()).stream()
                    .anyMatch(other -> !other.isStatic()
                            && other.erasedSignatureKey().equals(method.erasedSignatureKey()));
            if (overrides) {
                return true;
            }
        }
        return false;
    }

    private void assign(Expression target, SymbolicValue assigned,
                        Map<String, SymbolicValue> environment) {
        if (target instanceof NameExpression name && environment.containsKey(name.name())) {
            IrType declaredType = environment.get(name.name()).type();
            environment.put(name.name(), assigned.withType(declaredType));
        } else {
            publish(assigned);
            value(target, environment);
        }
    }

    private SymbolicValue map(ReturnOrigin origin, SymbolicValue receiver,
                              List<SymbolicValue> arguments) {
        return switch (origin.kind()) {
            case THIS -> receiver;
            case PARAMETER -> origin.parameterIndex() < arguments.size()
                    ? arguments.get(origin.parameterIndex()) : SymbolicValue.unknown(null);
            case ELEMENT_OF_PARAMETER -> {
                if (origin.parameterIndex() >= arguments.size()) {
                    yield SymbolicValue.unknown(null);
                }
                SymbolicValue argument = arguments.get(origin.parameterIndex());
                Set<ReturnOrigin> elements = elementsOf(argument.origins());
                yield new SymbolicValue(elements, Set.of(), Set.of(), null,
                        argument.mayBeNonOrigin() || elements.isEmpty(), false);
            }
        };
    }

    private void publish(SymbolicValue value) {
        nonReturnEscaping.addAll(value.origins());
        value.borrowedOrigins().stream()
                .map(BorrowedReturnOrigin::ownerOrigin)
                .forEach(nonReturnEscaping::add);
        escapingFreshOrigins.addAll(value.freshOrigins());
    }

    private void publishEnvironment(Map<String, SymbolicValue> environment) {
        environment.values().forEach(this::publish);
    }

    private boolean capturesImplicitEnclosingInstance(NewExpression allocation) {
        TypeSymbol allocated = resolver.resolve(allocation.className(), owner)
                .type().orElse(null);
        if (allocated == null || !allocated.isInnerClass()) {
            return false;
        }
        TypeSymbol required = allocated.enclosingType().orElse(null);
        for (TypeSymbol current = owner; current != null;
             current = current.enclosingType().orElse(null)) {
            if (isSubtype(current, required)) {
                return true;
            }
            if (!current.isInnerClass()) {
                break;
            }
        }
        return false;
    }

    private static boolean isSubtype(TypeSymbol actual, TypeSymbol expected) {
        if (actual == null || expected == null) {
            return false;
        }
        Set<String> visited = new LinkedHashSet<>();
        for (TypeSymbol current = actual; current != null;
             current = current.superclass().orElse(null)) {
            if (!visited.add(current.name())) {
                return false;
            }
            if (current.name().equals(expected.name())) {
                return true;
            }
        }
        return false;
    }

    private IrType sourceType(TypeName type) {
        if (type == null) {
            return null;
        }
        if (type.kind() == TypeName.Kind.REFERENCE) {
            TypeSymbol resolved = resolver.resolve(type.referenceName(), owner).type().orElse(null);
            return resolved == null ? IrType.reference(type.referenceName()) : resolved.selfType();
        }
        if (type.kind() == TypeName.Kind.ARRAY) {
            IrType element = sourceType(type.elementType());
            return element == null ? null : IrType.array(element);
        }
        return null;
    }

    private TypeSymbol symbol(IrType type) {
        if (type == null) {
            return null;
        }
        IrType erased = type.erasure();
        return erased.isNominalReference() ? types.get(erased.referenceName()) : null;
    }

    private static Set<ReturnOrigin> elementsOf(Set<ReturnOrigin> origins) {
        LinkedHashSet<ReturnOrigin> result = new LinkedHashSet<>();
        for (ReturnOrigin origin : origins) {
            if (origin.kind() == ReturnOrigin.Kind.PARAMETER) {
                result.add(ReturnOrigin.elementOfParameter(origin.parameterIndex()));
            }
        }
        return immutable(result);
    }

    private static Set<ReturnOrigin> dependencyOrigins(SymbolicValue value) {
        LinkedHashSet<ReturnOrigin> result = new LinkedHashSet<>(value.origins());
        value.borrowedOrigins().stream()
                .map(BorrowedReturnOrigin::ownerOrigin)
                .forEach(result::add);
        return immutable(result);
    }

    private static Map<String, SymbolicValue> copy(Map<String, SymbolicValue> environment) {
        return new LinkedHashMap<>(environment);
    }

    @SafeVarargs
    private static void mergeExisting(Map<String, SymbolicValue> target,
                                      Map<String, SymbolicValue>... incoming) {
        for (String name : Set.copyOf(target.keySet())) {
            SymbolicValue merged = null;
            for (Map<String, SymbolicValue> environment : incoming) {
                SymbolicValue value = environment.get(name);
                if (value != null) {
                    merged = merged == null ? value : SymbolicValue.merge(merged, value);
                }
            }
            if (merged != null) {
                target.put(name, merged);
            }
        }
    }

    private static Set<ReturnOrigin> immutable(Set<ReturnOrigin> origins) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(origins));
    }

    private static Set<BorrowedReturnOrigin> immutableBorrows(
            Set<BorrowedReturnOrigin> origins) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(origins));
    }

    private record SymbolicValue(Set<ReturnOrigin> origins,
                                 Set<BorrowedReturnOrigin> borrowedOrigins,
                                 Set<Expression> freshOrigins,
                                 IrType type,
                                 boolean mayBeNonOrigin, boolean mayBeNull) {
        private SymbolicValue {
            origins = immutable(origins);
            borrowedOrigins = immutableBorrows(borrowedOrigins);
            freshOrigins = Collections.unmodifiableSet(new LinkedHashSet<>(freshOrigins));
        }

        private static SymbolicValue exact(ReturnOrigin origin, IrType type) {
            return new SymbolicValue(Set.of(origin), Set.of(), Set.of(), type,
                    false, false);
        }

        private static SymbolicValue unknown(IrType type) {
            return new SymbolicValue(Set.of(), Set.of(), Set.of(), type,
                    true, false);
        }

        private static SymbolicValue fresh(Expression origin, IrType type) {
            return new SymbolicValue(Set.of(), Set.of(), Set.of(origin), type,
                    false, false);
        }

        private static SymbolicValue nullValue() {
            return new SymbolicValue(Set.of(), Set.of(), Set.of(), IrType.NULL,
                    false, true);
        }

        private SymbolicValue withType(IrType newType) {
            return new SymbolicValue(origins, borrowedOrigins, freshOrigins, newType,
                    mayBeNonOrigin, mayBeNull);
        }

        private static SymbolicValue merge(SymbolicValue left, SymbolicValue right) {
            LinkedHashSet<ReturnOrigin> origins = new LinkedHashSet<>(left.origins);
            origins.addAll(right.origins);
            LinkedHashSet<BorrowedReturnOrigin> borrowedOrigins =
                    new LinkedHashSet<>(left.borrowedOrigins);
            borrowedOrigins.addAll(right.borrowedOrigins);
            LinkedHashSet<Expression> freshOrigins = new LinkedHashSet<>(left.freshOrigins);
            freshOrigins.addAll(right.freshOrigins);
            IrType type = left.type != null && left.type.equals(right.type) ? left.type
                    : left.type == null ? right.type : right.type == null ? left.type : null;
            return new SymbolicValue(origins, borrowedOrigins, freshOrigins, type,
                    left.mayBeNonOrigin || right.mayBeNonOrigin,
                    left.mayBeNull || right.mayBeNull);
        }
    }

    private static final class ReturnAccumulator {
        private final Set<ReturnOrigin> origins = new LinkedHashSet<>();
        private final Set<BorrowedReturnOrigin> borrowedOrigins = new LinkedHashSet<>();
        private final Set<Expression> freshOrigins = new LinkedHashSet<>();
        private boolean mayReturnNonOrigin;
        private boolean mayReturnNull;
    }

    record ReturnSummary(Set<ReturnOrigin> returnedOrigins,
                         Set<BorrowedReturnOrigin> borrowedReturnedOrigins,
                         Set<ReturnOrigin> nonReturnEscapingOrigins,
                         boolean mayReturnNonOrigin, boolean mayReturnFresh,
                         boolean mayReturnNull, boolean freshEscapes) {
        ReturnSummary {
            returnedOrigins = immutable(returnedOrigins);
            borrowedReturnedOrigins = immutableBorrows(borrowedReturnedOrigins);
            nonReturnEscapingOrigins = immutable(nonReturnEscapingOrigins);
        }

        static ReturnSummary empty() {
            return new ReturnSummary(Set.of(), Set.of(), Set.of(),
                    false, false, false, false);
        }

        ReturnSummary merge(ReturnSummary other) {
            LinkedHashSet<ReturnOrigin> returned = new LinkedHashSet<>(returnedOrigins);
            returned.addAll(other.returnedOrigins);
            LinkedHashSet<BorrowedReturnOrigin> borrowed =
                    new LinkedHashSet<>(borrowedReturnedOrigins);
            borrowed.addAll(other.borrowedReturnedOrigins);
            LinkedHashSet<ReturnOrigin> escaping = new LinkedHashSet<>(nonReturnEscapingOrigins);
            escaping.addAll(other.nonReturnEscapingOrigins);
            return new ReturnSummary(returned, borrowed, escaping,
                    mayReturnNonOrigin || other.mayReturnNonOrigin,
                    mayReturnFresh || other.mayReturnFresh,
                    mayReturnNull || other.mayReturnNull,
                    freshEscapes || other.freshEscapes);
        }
    }

    record BorrowedReturnOrigin(ReturnOrigin ownerOrigin, String helperType) {
    }

    private record Receiver(TypeSymbol type, SymbolicValue value, ReceiverKind kind) {
    }

    private enum ReceiverKind {
        IMPLICIT,
        STATIC_TYPE,
        INSTANCE,
        SUPER
    }
}
