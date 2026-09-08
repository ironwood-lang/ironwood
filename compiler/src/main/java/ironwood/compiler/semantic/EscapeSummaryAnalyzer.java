// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.AssignmentExpression;
import ironwood.compiler.ast.ArrayAccessExpression;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EmptyStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.InstanceOfExpression;
import ironwood.compiler.ast.InstanceInitialization;
import ironwood.compiler.ast.InterfaceSuperExpression;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.LocalClassDeclaration;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.QualifiedThisExpression;
import ironwood.compiler.ast.QualifiedSuperConstructorExpression;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.SuperConstructorInvocation;
import ironwood.compiler.ast.SuperExpression;
import ironwood.compiler.ast.SwitchExpression;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchRuleThrow;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.ThisExpression;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UpdateExpression;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Computes a deliberately conservative closed-world summary for reference inputs.
 * A summary says only whether an input may outlive or be invalidated by a call;
 * uncertain nested calls and structured exceptional/loop flow count as escape.
 */
final class EscapeSummaryAnalyzer {
    private static final int THIS_ORIGIN = -1;

    private final Map<String, EscapeSummary> summaries = new LinkedHashMap<>();
    private final Map<String, CallableSymbol> callables = new LinkedHashMap<>();
    private final Map<String, Map<Integer, FieldSymbol>> retainedParameterFields =
            new LinkedHashMap<>();
    private final Map<String, TypeSymbol> types;
    private final TypeResolver resolver;
    private final OwnedArrayFieldAnalyzer ownedFields;
    private final BorrowDispatchAnalysis borrowDispatch;
    private final Deque<Set<Integer>> switchYields = new ArrayDeque<>();
    private TypeSymbol analyzingOwner;
    private CallableSymbol analyzingCallable;
    private Set<Integer> retainedByReceiver = Set.of();
    private Map<Integer, FieldSymbol> currentRetainedParameterFields = Map.of();
    private Set<Integer> ambiguousRetainedParameterFields = Set.of();

    EscapeSummaryAnalyzer(Map<String, TypeSymbol> types) {
        this(types, new TypeResolver(types), null);
    }

    EscapeSummaryAnalyzer(Map<String, TypeSymbol> types, TypeResolver resolver) {
        this(types, resolver, null);
    }

    EscapeSummaryAnalyzer(Map<String, TypeSymbol> types, TypeResolver resolver,
                          OwnedArrayFieldAnalyzer ownedFields) {
        this(types, resolver, ownedFields, null);
    }

    EscapeSummaryAnalyzer(Map<String, TypeSymbol> types, TypeResolver resolver,
                          OwnedArrayFieldAnalyzer ownedFields, BorrowDispatchAnalysis borrowDispatch) {
        this.borrowDispatch = borrowDispatch;
        this.types = types;
        this.resolver = resolver;
        this.ownedFields = ownedFields;
        // Start the finite escape fixed point at no escape. Unresolved calls still
        // add all origins; cycles of fully resolved borrowing calls need not escape.
        for (TypeSymbol type : types.values()) {
            type.constructors().forEach(callable -> callables.put(callable.linkageName(), callable));
            type.declaredMethods().values().forEach(callable -> callables.put(callable.linkageName(), callable));
            type.constructors().forEach(callable -> summaries.put(callable.linkageName(),
                    new EscapeSummary(false, Set.of(), Set.of())));
            type.declaredMethods().values().forEach(callable -> summaries.put(callable.linkageName(),
                    new EscapeSummary(false, Set.of(), Set.of())));
        }
        analyzeAll();
        int callableCount = types.values().stream().mapToInt(type ->
                (type.isInterface() ? 0 : type.constructors().size())
                        + type.declaredMethods().size()).sum();
        for (int pass = 0; pass <= callableCount; pass++) {
            Map<String, EscapeSummary> before = Map.copyOf(summaries);
            analyzeAll();
            if (summaries.equals(before)) {
                break;
            }
        }
        Map<String, SymbolicReturnOriginAnalyzer.ReturnSummary> returnedOrigins =
                new SymbolicReturnOriginAnalyzer(types, ownedFields, this).analyze();
        summaries.replaceAll((linkageName, summary) -> summary.withSymbolicReturnSummary(
                returnedOrigins.getOrDefault(linkageName,
                        SymbolicReturnOriginAnalyzer.ReturnSummary.empty())));
        for (TypeSymbol type : types.values()) {
            if (!type.isInterface()) {
                type.constructors().forEach(this::applyAuditedBorrowingContract);
            }
            type.declaredMethods().values().forEach(this::applyAuditedBorrowingContract);
        }
    }

    private void analyzeAll() {
        for (TypeSymbol type : types.values()) {
            if (!type.isInterface()) {
                type.constructors().forEach(this::analyze);
            }
            type.declaredMethods().values().forEach(this::analyze);
        }
    }

    EscapeSummary summary(CallableSymbol callable) {
        return summaries.getOrDefault(callable.linkageName(), EscapeSummary.unknown(callable));
    }

    EscapeSummary summary(String linkageName) {
        return summaries.get(linkageName);
    }

    CallableSymbol callable(String linkageName) { return callables.get(linkageName); }

    FieldSymbol retainedParameterField(CallableSymbol callable, int index) {
        return retainedParameterFields.getOrDefault(callable.linkageName(), Map.of()).get(index);
    }

    private void applyAuditedBorrowingContract(CallableSymbol callable) {
        EscapeSummary summary = summaries.get(callable.linkageName());
        if (summary == null) {
            return;
        }
        boolean borrowReceiver = false;
        boolean borrowParameters = false;
        if (DataStructureSemantics.borrowsReceiver(callable)) {
            // Entry nodes stay inside the containing structure or escape only
            // through dependent entry/iterator results. Payload effects remain.
            borrowReceiver = true;
        } else if (isBorrowingFilesFacade(callable)) {
            borrowParameters = true;
        } else if (callable.ownerType().equals("ironwood.io.StreamSupport")
                && callable.isStatic()) {
            // Native descriptor helpers observe their source arguments only for
            // the duration of the call and never retain managed references.
            borrowParameters = true;
        } else if (isBorrowingFileConstructor(callable)) {
            // File adapters retain only native descriptors or newly constructed
            // stream wrappers. Names, paths, modes, and flags are constructor-local.
            borrowParameters = true;
        } else if (callable.ownerType().equals("ironwood.nio.file.Path")) {
            borrowReceiver = !callable.isStatic();
            borrowParameters = true;
        } else if (callable.ownerType().equals("ironwood.nio.file.UnixPath")) {
            borrowReceiver = !callable.isConstructor();
            borrowParameters = true;
        } else if (callable.ownerType().equals("ironwood.lang.StringBuilder")
                && callable.sourceName().equals("append") && !callable.isStatic()) {
            // Every Java-shaped append overload observes its argument during the call,
            // returns this, and retains neither reference after the call completes.
            borrowReceiver = true;
            borrowParameters = true;
        } else if (callable.ownerType().equals("ironwood.util.StringJoiner")) {
            // Constructors and mutators copy callback text into owned character
            // storage. No supplied sequence or peer joiner is retained.
            borrowReceiver = !callable.isConstructor();
            borrowParameters = true;
        } else if (isBorrowingStandardInputRead(callable)) {
            // These audited standard-library implementations read into the supplied
            // buffer during the call and retain neither it nor their receiver.
            borrowReceiver = true;
            borrowParameters = true;
        }
        if (callable.ownerType().equals("ironwood.lang.Throwable") && callable.isConstructor()
                && throwableCaptureOverridesBorrowReceiver()) {
            // Default construction stores native state on the receiver and discards
            // fillInStackTrace's receiver alias. Keep real override publication.
            borrowReceiver = true;
        }
        if (ThrowableSemantics.isDescription(callable) && throwableMessageGettersBorrowReceiver()) {
            // Description copies the getter result and retains no receiver. Do not
            // erase a real publication or an unknown effect in a getter override.
            borrowReceiver = true;
        }
        if (borrowReceiver || borrowParameters) {
            summaries.put(callable.linkageName(), summary.withBorrowingContract(
                    borrowReceiver, borrowParameters));
        }
    }

    private boolean throwableCaptureOverridesBorrowReceiver() {
        for (TypeSymbol type : types.values()) {
            if (!ThrowableSemantics.isThrowable(type)) continue;
            for (CallableSymbol method : type.declaredMethodsNamed("fillInStackTrace")) {
                if (!method.isStatic() && method.parameterTypes().isEmpty()
                        && summary(method).thisEscapesWithoutReturn()) return false;
            }
        }
        return true;
    }

    private boolean throwableMessageGettersBorrowReceiver() {
        boolean found = false;
        for (TypeSymbol type : types.values()) {
            if (type.isInterface() || !ThrowableSemantics.isThrowable(type)) {
                continue;
            }
            CallableSymbol getter = ThrowableSemantics.messageGetter(type);
            if (getter == null || summary(getter).thisEscapesWithoutReturn()) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private static boolean isBorrowingStandardInputRead(CallableSymbol callable) {
        if (callable.isStatic() || !callable.sourceName().equals("read")) {
            return false;
        }
        return switch (callable.ownerType()) {
            case "ironwood.io.InputStream", "ironwood.io.BufferedInputStream",
                    "ironwood.io.ByteArrayInputStream", "ironwood.io.FileInputStream",
                    "ironwood.io.StandardInputStream" -> true;
            default -> false;
        };
    }

    private boolean compatibleDelegation(CallableSymbol target, java.util.List<Expression> arguments) {
        for (int index = 0; index < arguments.size(); index++) {
            IrType actual = null;
            if (arguments.get(index) instanceof NameExpression name) {
                for (int parameter = 0; parameter < analyzingCallable.parameters().size(); parameter++) {
                    if (analyzingCallable.parameters().get(parameter).name().equals(name.name())) {
                        actual = analyzingCallable.parameterTypes().get(parameter);
                    }
                }
                if (actual == null) {
                    FieldSymbol field = analyzingOwner.declaredFields().get(name.name());
                    if (field != null) { actual = field.type(); }
                }
            }
            if (actual != null && actual.isReference() != target.parameterTypes().get(index).isReference()) {
                return false;
            }
        }
        return true;
    }

    private void analyze(CallableSymbol callable) {
        SymbolicReturnOriginAnalyzer.ReturnSummary poolContract = PoolSemantics.symbolic(callable);
        if (poolContract != null) {
            summaries.put(callable.linkageName(), new EscapeSummary(false, Set.of(), Set.of())
                    .withSymbolicReturnSummary(poolContract));
            return;
        }

        analyzingOwner = types.get(callable.ownerType());
        analyzingCallable = callable;
        Set<Integer> escaped = new LinkedHashSet<>();
        Set<Integer> retained = new LinkedHashSet<>();
        retainedByReceiver = retained;
        currentRetainedParameterFields = new LinkedHashMap<>();
        ambiguousRetainedParameterFields = new LinkedHashSet<>();
        Map<String, Set<Integer>> environment = new LinkedHashMap<>();
        for (int index = 0; index < callable.parameters().size(); index++) {
            environment.put(callable.parameters().get(index).name(), Set.of(index));
        }
        if (callable.isConstructor()) {
            callable.superInvocation().ifPresent(invocation -> invocation.arguments().forEach(argument ->
                    markEscaped(origins(argument, environment, escaped, false), escaped)));
        }
        if (callable.isConstructor() && callable.thisInvocation().isPresent()) {
            var invocation = callable.thisInvocation().orElseThrow();
            var targets = analyzingOwner.constructors().stream()
                    .filter(candidate -> candidate != callable)
                    .filter(candidate -> candidate.parameters().size() == invocation.arguments().size())
                    .filter(candidate -> compatibleDelegation(candidate, invocation.arguments())).toList();
            CallableSymbol target = targets.size() == 1 ? targets.getFirst() : null;
            EscapeSummary delegated = target == null ? null : summary(target);
            if (delegated == null || delegated.thisEscapes()) { escaped.add(THIS_ORIGIN); }
            for (int index = 0; index < invocation.arguments().size(); index++) {
                Set<Integer> argument = origins(invocation.arguments().get(index), environment, escaped, false);
                if (delegated == null || delegated.parameterEscapesOutsideReceiver(index)) {
                    markEscaped(argument, escaped);
                } else if (delegated.parameterRetainedByReceiverOnly(index)) {
                    retained.addAll(argument);
                    FieldSymbol field = retainedParameterField(target, index);
                    for (int origin : argument) {
                        FieldSymbol previous = field == null ? null
                                : currentRetainedParameterFields.putIfAbsent(origin, field);
                        if (field == null || previous != null && previous != field) {
                            ambiguousRetainedParameterFields.add(origin);
                        }
                    }
                }
            }
        }
        if (callable.ownerType().equals("ironwood.lang.System")
                && callable.sourceName().equals("arraycopy")
                && callable.isStatic()
                && callable.parameters().size() == 5) {
            // The destination may retain aliases copied from a reference array. The current
            // allocation proof does not model per-element provenance, so reject a later free
            // of a tracked destination rather than risk losing an alias edge.
            escaped.add(2);
        }
        if (callable.isConstructor() && callable.thisInvocation().isEmpty()) {
            TypeSymbol owner = types.get(callable.ownerType());
            ClassDeclaration declaration = (ClassDeclaration) owner.declaration();
            Map<String, Set<Integer>> initializerEnvironment = new LinkedHashMap<>();
            for (InstanceInitialization initialization : declaration.instanceInitializations()) {
                if (initialization instanceof FieldDeclaration field) {
                    field.initializer().ifPresent(expression ->
                            origins(expression, initializerEnvironment, escaped, false));
                } else {
                    scanBlock((Block) initialization, initializerEnvironment, escaped, true, false);
                }
            }
        }
        callable.body().ifPresent(body -> scanBlock(body, environment, escaped, false, callable.isStatic()));
        if (callable.ownerType().equals("ironwood.nio.ByteBuffer")
                && callable.sourceName().equals("array") && !callable.isStatic()
                && callable.parameterTypes().isEmpty()) {
            // Publishing a backing array prevents safe reclamation of an owning
            // buffer. Wrapped storage is caller-owned, but keep one conservative
            // source-level contract for every dispatch target of array().
            escaped.add(THIS_ORIGIN);
        }
        if (isBorrowingFilesFacade(callable)) {
            // These Java-shaped whole-file operations observe their arguments only for the
            // duration of the call. Their implementation crosses typed native intrinsics, so
            // preserve that audited borrowing contract instead of allowing an abstract
            // Path.toString dispatch to make every Path permanently escaping.
            for (int index = 0; index < callable.parameters().size(); index++) {
                escaped.remove(index);
            }
        }
        if (callable.isConstructor()
                && callable.ownerType().equals("ironwood.nio.file.UnixPath")
                && (callable.parameters().size() == 1 || callable.parameters().size() == 2
                || callable.parameters().size() == 3)) {
            escaped.remove(0);
            escaped.remove(1);
        }
        summaries.put(callable.linkageName(), new EscapeSummary(
                escaped.contains(THIS_ORIGIN), escaped.stream().filter(value -> value >= 0).collect(
                        java.util.stream.Collectors.toUnmodifiableSet()), retained));
        Map<Integer, FieldSymbol> retainedFields = new LinkedHashMap<>(
                currentRetainedParameterFields);
        ambiguousRetainedParameterFields.forEach(retainedFields::remove);
        retainedParameterFields.put(callable.linkageName(), Map.copyOf(retainedFields));
    }

    private static boolean isBorrowingFilesFacade(CallableSymbol callable) {
        if (!callable.ownerType().equals("ironwood.nio.file.Files") || !callable.isStatic()) {
            return false;
        }
        return switch (callable.sourceName()) {
            case "newInputStream", "newOutputStream", "newBufferedReader", "newBufferedWriter", "isSameFile",
                    "readAllBytes", "readString", "readAllLines", "write", "writeString", "delete",
                    "createDirectories", "copy", "move", "exists",
                    "isRegularFile", "isDirectory", "isSymbolicLink", "newDirectoryStream",
                    "readAttributes", "openDirectory", "directoryHasNext",
                    "nextDirectoryEntry", "closeDirectory",
                    "walkFileTree", "walkEntry", "createsTraversalLoop", "checkedVisitResult",
                    "visitFailed", "postDirectory",
                    "releaseVisitedPath", "releaseVisitedAttributes",
                    "releaseVisitedDirectoryStream",
                    "size" -> true;
            default -> false;
        };
    }

    private static boolean isBorrowingFileConstructor(CallableSymbol callable) {
        if (!callable.isConstructor() || callable.parameterTypes().isEmpty()) {
            return false;
        }
        IrType first = callable.parameterTypes().getFirst();
        if (!first.equals(IrType.reference("ironwood.lang.String"))
                && !first.equals(IrType.reference("ironwood.nio.file.Path"))) {
            return false;
        }
        return switch (callable.ownerType()) {
            case "ironwood.io.FileInputStream", "ironwood.io.FileOutputStream",
                    "ironwood.io.FileReader", "ironwood.io.FileWriter",
                    "ironwood.io.InputStreamReader", "ironwood.io.OutputStreamWriter",
                    "ironwood.io.RandomAccessFile", "ironwood.io.BufferedReader",
                    "ironwood.io.BufferedWriter" -> true;
            default -> false;
        };
    }

    private void scanBlock(Block block, Map<String, Set<Integer>> environment,
                           Set<Integer> escaped, boolean scoped, boolean staticFunction) {
        Set<String> existing = Set.copyOf(environment.keySet());
        for (Statement statement : block.statements()) {
            scanStatement(statement, environment, escaped, staticFunction);
        }
        if (scoped) {
            environment.keySet().removeIf(name -> !existing.contains(name));
        }
    }

    private void scanStatement(Statement statement, Map<String, Set<Integer>> environment,
                               Set<Integer> escaped, boolean staticFunction) {
        if (statement instanceof Block block) {
            scanBlock(block, environment, escaped, true, staticFunction);
            return;
        }
        if (statement instanceof LocalVariableDeclaration declaration) {
            environment.put(declaration.name(), origins(declaration.initializer(), environment,
                    escaped, staticFunction));
            return;
        }
        if (statement instanceof LocalClassDeclaration) {
            return;
        }
        if (statement instanceof AssignmentStatement assignment) {
            Set<Integer> value = originsForAssignment(assignment.target(), assignment.value(),
                    environment, escaped, staticFunction);
            if (assignment.target() instanceof NameExpression name && environment.containsKey(name.name())) {
                environment.put(name.name(), value);
            } else if (isReceiverFieldTarget(assignment.target(), environment, staticFunction)) {
                retainedByReceiver.addAll(value);
                recordRetainedParameterFields(assignment.target(), value, environment,
                        staticFunction);
                origins(assignment.target(), environment, escaped, staticFunction);
            } else {
                markEscaped(value, escaped);
                origins(assignment.target(), environment, escaped, staticFunction);
            }
            return;
        }
        if (statement instanceof ExpressionStatement expression) {
            origins(expression.expression(), environment, escaped, staticFunction);
            return;
        }
        if (statement instanceof FreeStatement free) {
            markEscaped(origins(free.value(), environment, escaped, staticFunction), escaped);
            return;
        }
        if (statement instanceof ReturnStatement returned) {
            returned.value().ifPresent(value -> markEscaped(
                    origins(value, environment, escaped, staticFunction), escaped));
            return;
        }
        if (statement instanceof ThrowStatement thrown) {
            markEscaped(origins(thrown.value(), environment, escaped, staticFunction), escaped);
            return;
        }
        if (statement instanceof SuperConstructorInvocation invocation) {
            invocation.enclosingInstance().ifPresent(enclosing -> markEscaped(
                    origins(enclosing, environment, escaped, staticFunction), escaped));
            invocation.arguments().forEach(argument -> markEscaped(
                    origins(argument, environment, escaped, staticFunction), escaped));
            return;
        }
        if (statement instanceof ThisConstructorInvocation invocation) {
            invocation.arguments().forEach(argument -> markEscaped(
                    origins(argument, environment, escaped, staticFunction), escaped));
            return;
        }
        if (statement instanceof IfStatement conditional) {
            origins(conditional.condition(), environment, escaped, staticFunction);
            Map<String, Set<Integer>> thenEnvironment = copy(environment);
            scanScopedStatement(conditional.thenBranch(), thenEnvironment, escaped, staticFunction);
            Map<String, Set<Integer>> elseEnvironment = copy(environment);
            conditional.elseBranch().ifPresent(branch ->
                    scanScopedStatement(branch, elseEnvironment, escaped, staticFunction));
            mergeExisting(environment, thenEnvironment, elseEnvironment);
            return;
        }
        if (statement instanceof WhileStatement loop) {
            origins(loop.condition(), environment, escaped, staticFunction);
            Map<String, Set<Integer>> bodyEnvironment = copy(environment);
            scanScopedStatement(loop.body(), bodyEnvironment, escaped, staticFunction);
            mergeExisting(environment, environment, bodyEnvironment);
            return;
        }
        if (statement instanceof DoWhileStatement loop) {
            Map<String, Set<Integer>> bodyEnvironment = copy(environment);
            scanScopedStatement(loop.body(), bodyEnvironment, escaped, staticFunction);
            origins(loop.condition(), bodyEnvironment, escaped, staticFunction);
            mergeExisting(environment, environment, bodyEnvironment);
            return;
        }
        if (statement instanceof ForStatement loop) {
            Map<String, Set<Integer>> loopEnvironment = copy(environment);
            loop.initializer().ifPresent(initializer ->
                    scanStatement(initializer, loopEnvironment, escaped, staticFunction));
            loop.condition().ifPresent(condition ->
                    origins(condition, loopEnvironment, escaped, staticFunction));
            Map<String, Set<Integer>> bodyEnvironment = copy(loopEnvironment);
            scanScopedStatement(loop.body(), bodyEnvironment, escaped, staticFunction);
            loop.updates().forEach(update ->
                    origins(update, bodyEnvironment, escaped, staticFunction));
            mergeExisting(environment, environment, bodyEnvironment);
            return;
        }
        if (statement instanceof EnhancedForStatement loop) {
            Set<Integer> iterable = origins(loop.iterable(), environment, escaped, staticFunction);
            markEscaped(iterable, escaped);
            Map<String, Set<Integer>> bodyEnvironment = copy(environment);
            bodyEnvironment.put(loop.variableName(), Set.of());
            scanScopedStatement(loop.body(), bodyEnvironment, escaped, staticFunction);
            mergeExisting(environment, environment, bodyEnvironment);
            return;
        }
        if (statement instanceof LabeledStatement labeled) {
            scanScopedStatement(labeled.body(), environment, escaped, staticFunction);
            return;
        }
        if (statement instanceof EmptyStatement) {
            return;
        }
        if (statement instanceof SwitchStatement switched) {
            origins(switched.selector(), environment, escaped, staticFunction);
            Map<String, Set<Integer>> merged = copy(environment);
            Map<String, Set<Integer>> fallthrough = copy(environment);
            for (var group : switched.groups()) {
                Map<String, Set<Integer>> groupEnvironment = copy(environment);
                mergeExisting(groupEnvironment, groupEnvironment, fallthrough);
                for (Statement child : group.statements()) {
                    scanStatement(child, groupEnvironment, escaped, staticFunction);
                }
                mergeExisting(merged, merged, groupEnvironment);
                fallthrough = groupEnvironment;
            }
            environment.clear();
            environment.putAll(merged);
            return;
        }
        if (statement instanceof ModernSwitchStatement switched) {
            origins(switched.selector(), environment, escaped, staticFunction);
            Map<String, Set<Integer>> merged = copy(environment);
            for (var rule : switched.rules()) {
                Map<String, Set<Integer>> branch = copy(environment);
                scanSwitchRuleBody(rule.body(), branch, escaped, staticFunction);
                mergeExisting(merged, merged, branch);
            }
            environment.clear();
            environment.putAll(merged);
            return;
        }
        if (statement instanceof YieldStatement yielded) {
            Set<Integer> result = origins(yielded.value(), environment, escaped, staticFunction);
            if (!switchYields.isEmpty()) {
                switchYields.peek().addAll(result);
            }
            return;
        }
        if (statement instanceof ironwood.compiler.ast.BreakStatement
                || statement instanceof ironwood.compiler.ast.ContinueStatement) {
            return;
        }
        TryStatement guarded = (TryStatement) statement;
        Map<String, Set<Integer>> merged = copy(environment);
        Map<String, Set<Integer>> tryEnvironment = copy(environment);
        scanBlock(guarded.body(), tryEnvironment, escaped, true, staticFunction);
        mergeExisting(merged, merged, tryEnvironment);
        guarded.catches().forEach(caught -> {
            Map<String, Set<Integer>> catchEnvironment = copy(environment);
            catchEnvironment.put(caught.variableName(), Set.of());
            scanBlock(caught.body(), catchEnvironment, escaped, true, staticFunction);
            mergeExisting(merged, merged, catchEnvironment);
        });
        guarded.finallyBlock().ifPresent(cleanup ->
                scanBlock(cleanup, merged, escaped, true, staticFunction));
        environment.clear();
        environment.putAll(merged);
    }

    private void scanScopedStatement(Statement statement, Map<String, Set<Integer>> environment,
                                     Set<Integer> escaped, boolean staticFunction) {
        Set<String> existing = Set.copyOf(environment.keySet());
        scanStatement(statement, environment, escaped, staticFunction);
        environment.keySet().removeIf(name -> !existing.contains(name));
    }

    private Set<Integer> origins(Expression expression, Map<String, Set<Integer>> environment,
                                 Set<Integer> escaped, boolean staticFunction) {
        if (expression instanceof SwitchExpression switched) {
            origins(switched.selector(), environment, escaped, staticFunction);
            Set<Integer> results = new LinkedHashSet<>();
            switchYields.push(results);
            Map<String, Set<Integer>> merged = copy(environment);
            if (switched.arrowRules()) {
                for (var rule : switched.rules()) {
                    Map<String, Set<Integer>> branch = copy(environment);
                    if (rule.body() instanceof SwitchRuleExpression result) {
                        results.addAll(origins(result.expression(), branch, escaped, staticFunction));
                    } else {
                        scanSwitchRuleBody(rule.body(), branch, escaped, staticFunction);
                    }
                    mergeExisting(merged, merged, branch);
                }
            } else {
                Map<String, Set<Integer>> fallthrough = copy(environment);
                for (var group : switched.groups()) {
                    Map<String, Set<Integer>> branch = copy(environment);
                    mergeExisting(branch, branch, fallthrough);
                    for (Statement child : group.statements()) {
                        scanStatement(child, branch, escaped, staticFunction);
                    }
                    mergeExisting(merged, merged, branch);
                    fallthrough = branch;
                }
            }
            switchYields.pop();
            environment.clear();
            environment.putAll(merged);
            return Set.copyOf(results);
        }
        if (expression instanceof NameExpression name) {
            return environment.getOrDefault(name.name(), Set.of());
        }
        if (expression instanceof ThisExpression || expression instanceof SuperExpression
                || expression instanceof InterfaceSuperExpression
                || expression instanceof QualifiedThisExpression) {
            return staticFunction ? Set.of() : Set.of(THIS_ORIGIN);
        }
        if (expression instanceof FieldAccessExpression access) {
            origins(access.receiver(), environment, escaped, staticFunction);
            return Set.of();
        }
        if (expression instanceof ArrayAccessExpression access) {
            origins(access.array(), environment, escaped, staticFunction);
            origins(access.index(), environment, escaped, staticFunction);
            return Set.of();
        }
        if (expression instanceof ArrayCreationExpression creation) {
            creation.length().ifPresent(length ->
                    origins(length, environment, escaped, staticFunction));
            creation.initializer().ifPresent(initializer ->
                    origins(initializer, environment, escaped, staticFunction));
            return Set.of();
        }
        if (expression instanceof ArrayInitializerExpression initializer) {
            initializer.elements().forEach(element -> markEscaped(
                    origins(element, environment, escaped, staticFunction), escaped));
            return Set.of();
        }
        if (expression instanceof NewExpression allocation) {
            allocation.enclosingInstance().ifPresent(enclosing -> markEscaped(
                    origins(enclosing, environment, escaped, staticFunction), escaped));
            if (allocation.enclosingInstance().isEmpty() && !staticFunction
                    && capturesImplicitEnclosingInstance(allocation)) {
                escaped.add(THIS_ORIGIN);
            }
            TypeSymbol allocated = allocatedType(allocation);
            boolean copiesStorage = allocated != null
                    && (allocated.name().equals("ironwood.lang.String")
                    && (allocation.arguments().size() == 1
                    || allocation.arguments().size() == 3)
                    || allocated.name().equals("ironwood.nio.file.UnixPath")
                    && (allocation.arguments().size() == 1 || allocation.arguments().size() == 3)
                    || allocated.name().equals("ironwood.util.StringJoiner")
                    && (allocation.arguments().size() == 1 || allocation.arguments().size() == 3));
            allocation.arguments().forEach(argument -> {
                Set<Integer> argumentOrigins = origins(
                        argument, environment, escaped, staticFunction);
                if (!copiesStorage) {
                    markEscaped(argumentOrigins, escaped);
                }
            });
            if (allocated != null) {
                for (TypeSymbol.CaptureSlot capture : allocated.captureSlots()) {
                    markEscaped(environment.getOrDefault(capture.variable().name(), Set.of()), escaped);
                }
            }
            return Set.of();
        }
        if (expression instanceof QualifiedSuperConstructorExpression invocation) {
            markEscaped(origins(invocation.enclosingInstance(), environment, escaped, staticFunction),
                    escaped);
            invocation.arguments().forEach(argument -> markEscaped(
                    origins(argument, environment, escaped, staticFunction), escaped));
            return Set.of();
        }
        if (expression instanceof CallExpression call) {
            Set<Integer> receiverOrigins = call.receiver().isPresent()
                    ? origins(call.receiver().orElseThrow(), environment, escaped, staticFunction)
                    : staticFunction ? Set.of() : Set.of(THIS_ORIGIN);
            java.util.List<Set<Integer>> argumentOrigins = call.arguments().stream()
                    .map(argument -> origins(argument, environment, escaped, staticFunction))
                    .toList();
            CallableSymbol target = resolveCall(call, environment, staticFunction);
            if (target != null && target.ownerType().equals("ironwood.lang.System")
                    && target.sourceName().equals("arraycopy") && target.isStatic()
                    && call.arguments().size() == 5
                    && primitiveArrayParameterOrField(call.arguments().get(0), environment.keySet())
                    && primitiveArrayParameterOrField(call.arguments().get(2), environment.keySet())) {
                return Set.of();
            }
            if (isNonRetainingPrimitiveCall(analyzingOwner, analyzingCallable, call,
                    environment.keySet())) {
                return Set.of();
            }
            if (target == null) {
                if (isKnownBorrowingStringBuilderAppend(call, environment, staticFunction)) {
                    return receiverOrigins;
                }
                if (isKnownBorrowingPathText(call, environment)) {
                    return receiverOrigins;
                }
                if (isKnownNonEscapingStringCall(call, environment, staticFunction)) {
                    return Set.of();
                }
                markEscaped(receiverOrigins, escaped);
                argumentOrigins.forEach(origins -> markEscaped(origins, escaped));
                return Set.of();
            }
            EscapeSummary targetSummary = summary(target);
            if (!target.isStatic() && targetSummary.thisEscapesWithoutReturn()) {
                markEscaped(receiverOrigins, escaped);
            }
            for (int index = 0; index < argumentOrigins.size(); index++) {
                if (targetSummary.parameterEscapesWithoutReturn(index)) {
                    markEscaped(argumentOrigins.get(index), escaped);
                }
            }
            Set<Integer> returned = new LinkedHashSet<>();
            for (ReturnOrigin origin : targetSummary.returnedOrigins()) {
                if (origin.kind() == ReturnOrigin.Kind.THIS) {
                    returned.addAll(receiverOrigins);
                } else if (origin.kind() == ReturnOrigin.Kind.PARAMETER
                        && origin.parameterIndex() < argumentOrigins.size()) {
                    returned.addAll(argumentOrigins.get(origin.parameterIndex()));
                }
            }
            for (SymbolicReturnOriginAnalyzer.BorrowedReturnOrigin borrowed
                    : targetSummary.borrowedReturnedOrigins()) {
                ReturnOrigin origin = borrowed.ownerOrigin();
                if (origin.kind() == ReturnOrigin.Kind.THIS) {
                    returned.addAll(receiverOrigins);
                } else if (origin.kind() == ReturnOrigin.Kind.PARAMETER
                        && origin.parameterIndex() < argumentOrigins.size()) {
                    returned.addAll(argumentOrigins.get(origin.parameterIndex()));
                }
            }
            return Set.copyOf(returned);
        }
        if (expression instanceof BinaryExpression binary) {
            origins(binary.left(), environment, escaped, staticFunction);
            if (binary.operator() == ironwood.compiler.ast.BinaryOperator.LOGICAL_AND
                    || binary.operator() == ironwood.compiler.ast.BinaryOperator.LOGICAL_OR) {
                Map<String, Set<Integer>> withoutRight = copy(environment);
                Map<String, Set<Integer>> withRight = copy(environment);
                origins(binary.right(), withRight, escaped, staticFunction);
                mergeExisting(environment, withoutRight, withRight);
            } else {
                origins(binary.right(), environment, escaped, staticFunction);
            }
            return Set.of();
        }
        if (expression instanceof AssignmentExpression assignment) {
            Set<Integer> value = originsForAssignment(assignment.target(), assignment.value(),
                    environment, escaped, staticFunction);
            if (assignment.target() instanceof NameExpression name
                    && environment.containsKey(name.name())) {
                if (assignment.operator() == ironwood.compiler.ast.AssignmentOperator.ASSIGN) {
                    environment.put(name.name(), value);
                    return value;
                }
                origins(assignment.target(), environment, escaped, staticFunction);
                return Set.of();
            }
            if (isReceiverFieldTarget(assignment.target(), environment, staticFunction)) {
                retainedByReceiver.addAll(value);
                recordRetainedParameterFields(assignment.target(), value, environment,
                        staticFunction);
            } else {
                markEscaped(value, escaped);
            }
            origins(assignment.target(), environment, escaped, staticFunction);
            return assignment.operator() == ironwood.compiler.ast.AssignmentOperator.ASSIGN
                    ? value : Set.of();
        }
        if (expression instanceof ConditionalExpression conditional) {
            origins(conditional.condition(), environment, escaped, staticFunction);
            Map<String, Set<Integer>> trueEnvironment = copy(environment);
            Set<Integer> result = new LinkedHashSet<>(origins(
                    conditional.whenTrue(), trueEnvironment, escaped, staticFunction));
            Map<String, Set<Integer>> falseEnvironment = copy(environment);
            result.addAll(origins(conditional.whenFalse(), falseEnvironment, escaped, staticFunction));
            mergeExisting(environment, trueEnvironment, falseEnvironment);
            return Set.copyOf(result);
        }
        if (expression instanceof CastExpression cast) {
            return origins(cast.operand(), environment, escaped, staticFunction);
        }
        if (expression instanceof UpdateExpression update) {
            origins(update.target(), environment, escaped, staticFunction);
            return Set.of();
        }
        if (expression instanceof UnaryExpression unary) {
            return origins(unary.operand(), environment, escaped, staticFunction);
        }
        if (expression instanceof InstanceOfExpression typeTest) {
            origins(typeTest.operand(), environment, escaped, staticFunction);
        }
        return Set.of();
    }

    private boolean isKnownNonEscapingStringCall(CallExpression call,
                                                  Map<String, Set<Integer>> environment,
                                                  boolean staticFunction) {
        TypeSymbol receiver;
        if (call.receiver().isEmpty()) {
            receiver = staticFunction ? null : analyzingOwner;
        } else {
            receiver = receiverType(call.receiver().orElseThrow(), environment);
            if (receiver == null && call.receiver().orElseThrow() instanceof CallExpression nested
                    && isKnownBorrowingPathText(nested, environment)) {
                receiver = types.get("ironwood.lang.String");
            }
        }
        if (receiver == null || !receiver.name().equals("ironwood.lang.String")) {
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

    private boolean isKnownBorrowingPathText(CallExpression call,
                                             Map<String, Set<Integer>> environment) {
        if (!call.methodName().equals("toString") || !call.arguments().isEmpty()
                || call.receiver().isEmpty()) return false;
        TypeSymbol receiver = receiverType(call.receiver().orElseThrow(), environment);
        return receiver != null && (receiver.name().equals("ironwood.nio.file.Path")
                || receiver.name().equals("ironwood.nio.file.UnixPath"));
    }

    private boolean isKnownBorrowingStringBuilderAppend(
            CallExpression call, Map<String, Set<Integer>> environment,
            boolean staticFunction) {
        TypeSymbol receiver;
        if (call.receiver().isEmpty()) {
            receiver = staticFunction ? null : analyzingOwner;
        } else {
            receiver = receiverType(call.receiver().orElseThrow(), environment);
        }
        return receiver != null && receiver.name().equals("ironwood.lang.StringBuilder")
                && call.methodName().equals("append") && call.arguments().size() == 1;
    }

    private void scanSwitchRuleBody(ironwood.compiler.ast.SwitchRuleBody body,
                                    Map<String, Set<Integer>> environment,
                                    Set<Integer> escaped, boolean staticFunction) {
        if (body instanceof SwitchRuleExpression result) {
            origins(result.expression(), environment, escaped, staticFunction);
        } else if (body instanceof SwitchRuleBlock block) {
            scanBlock(block.block(), environment, escaped, true, staticFunction);
        } else if (body instanceof SwitchRuleThrow thrown) {
            scanStatement(thrown.statement(), environment, escaped, staticFunction);
        }
    }

    private Set<Integer> originsForAssignment(Expression target, Expression value,
                                              Map<String, Set<Integer>> environment,
                                              Set<Integer> escaped,
                                              boolean staticFunction) {
        FieldSymbol field = assignedReceiverField(target, environment, staticFunction);
        if (ownedFields == null || field == null || !ownedFields.isOwned(field)
                || !(value instanceof NewExpression allocation)) {
            return origins(value, environment, escaped, staticFunction);
        }
        TypeSymbol allocated = allocatedType(allocation);
        if (allocated == null) {
            return origins(value, environment, escaped, staticFunction);
        }
        java.util.List<CallableSymbol> constructors = allocated.constructors().stream()
                .filter(candidate -> candidate.parameters().size()
                        == allocation.arguments().size())
                .toList();
        if (constructors.size() != 1) {
            return origins(value, environment, escaped, staticFunction);
        }
        EscapeSummary constructor = summary(constructors.getFirst());
        allocation.enclosingInstance().ifPresent(enclosing -> markEscaped(
                origins(enclosing, environment, escaped, staticFunction), escaped));
        for (int index = 0; index < allocation.arguments().size(); index++) {
            Set<Integer> argument = origins(allocation.arguments().get(index), environment,
                    escaped, staticFunction);
            if (constructor.parameterEscapesOutsideReceiver(index)) {
                markEscaped(argument, escaped);
            } else if (constructor.parameterRetainedByReceiverOnly(index)) {
                FieldSymbol retainedField = retainedParameterFields
                        .getOrDefault(constructors.getFirst().linkageName(), Map.of())
                        .get(index);
                if (retainedField == null || !ownedFields.isEncapsulated(retainedField)) {
                    markEscaped(argument, escaped);
                }
            }
        }
        if (allocated.captureSlots().isEmpty()) {
            return Set.of();
        }
        // Captured locals need a richer per-capture retention summary. Until then, keep
        // the ordinary conservative behavior for an owned anonymous/local helper.
        for (TypeSymbol.CaptureSlot capture : allocated.captureSlots()) {
            markEscaped(environment.getOrDefault(capture.variable().name(), Set.of()), escaped);
        }
        return Set.of();
    }

    private boolean isReceiverFieldTarget(Expression expression,
                                          Map<String, Set<Integer>> environment,
                                          boolean staticFunction) {
        return assignedReceiverField(expression, environment, staticFunction) != null;
    }

    private void recordRetainedParameterFields(Expression target, Set<Integer> origins,
                                               Map<String, Set<Integer>> environment,
                                               boolean staticFunction) {
        FieldSymbol field = assignedReceiverField(target, environment, staticFunction);
        if (field == null) {
            return;
        }
        for (int origin : origins) {
            if (origin < 0) {
                continue;
            }
            FieldSymbol previous = field == null ? null
                                : currentRetainedParameterFields.putIfAbsent(origin, field);
            if (previous != null && previous != field) {
                ambiguousRetainedParameterFields.add(origin);
            }
        }
    }

    private FieldSymbol assignedReceiverField(Expression expression,
                                              Map<String, Set<Integer>> environment,
                                              boolean staticFunction) {
        if (analyzingCallable == null || !analyzingCallable.isConstructor()
                || staticFunction) {
            return null;
        }
        String name;
        if (expression instanceof NameExpression field
                && !environment.containsKey(field.name())) {
            name = field.name();
        } else if (expression instanceof FieldAccessExpression access
                && access.receiver() instanceof ThisExpression) {
            name = access.fieldName();
        } else {
            return null;
        }
        for (TypeSymbol current = analyzingOwner; current != null;
             current = current.superclass().orElse(null)) {
            FieldSymbol field = current.declaredFields().get(name);
            if (field != null) {
                return field.isStatic() ? null : field;
            }
        }
        return null;
    }

    private boolean primitiveArrayParameterOrField(Expression expression, Set<String> localNames) {
        if (!(expression instanceof NameExpression name)) { return false; }
        IrType type = null;
        for (int index = 0; index < analyzingCallable.parameters().size(); index++) {
            if (analyzingCallable.parameters().get(index).name().equals(name.name())) {
                type = analyzingCallable.parameterTypes().get(index);
            }
        }
        if (type == null && !localNames.contains(name.name())) {
            FieldSymbol field = analyzingOwner.declaredFields().get(name.name());
            if (field != null) { type = field.type(); }
        }
        return type != null && type.isArray() && type.elementType().isPrimitive();
    }

    /** Join the resolved call-site targets. Reference results keep their richer
     * symbolic-return analysis. The broad fallback is only for provisional IR. */
    boolean isNonRetainingPrimitiveCall(TypeSymbol owner, CallableSymbol caller,
                                        CallExpression call, Set<String> localNames) {
        if (borrowDispatch != null) {
            return isNonRetainingPrimitiveCall(caller.linkageName(), call.span(), call.methodName());
        }
        TypeSymbol receiver = owner;
        boolean staticCall = false;
        if (call.receiver().isPresent()) {
            Expression expression = call.receiver().orElseThrow();
            if (!(expression instanceof ThisExpression) && !(expression instanceof SuperExpression)) {
                String name = qualifiedName(expression);
                IrType type = null;
                if (expression instanceof NameExpression variable) {
                    for (int index = 0; index < caller.parameters().size(); index++) {
                        if (caller.parameters().get(index).name().equals(variable.name())) {
                            type = caller.parameterTypes().get(index);
                        }
                    }
                    if (type == null && !localNames.contains(variable.name())) {
                        FieldSymbol field = owner.declaredFields().get(variable.name());
                        if (field != null) { type = field.type(); }
                    }
                } else if (expression instanceof FieldAccessExpression field
                        && field.receiver() instanceof ThisExpression) {
                    FieldSymbol symbol = owner.declaredFields().get(field.fieldName());
                    if (symbol != null) { type = symbol.type(); }
                }
                if (type != null) {
                    receiver = type.isNominalReference() ? types.get(type.referenceName()) : null;
                } else if (name != null && !localNames.contains(name)) {
                    receiver = resolver.resolve(name, owner, expression.span()).type().orElse(null);
                    staticCall = true;
                } else { return false; }
            }
        }
        if (receiver == null) { return false; }
        return isNonRetainingPrimitiveDispatch(receiver, call.methodName(), call.arguments().size(), staticCall);
    }

    boolean isNonRetainingPrimitiveCall(String caller, SourceSpan span, String name) {
        if (borrowDispatch == null) { return false; }
        Set<String> targets = borrowDispatch.primitiveTargets(caller, span, name);
        if (targets.isEmpty()) { return false; }
        return targets.stream().allMatch(this::isNonRetaining);
    }

    boolean isNonRetaining(String target) {
        // Primitive parameter origins can occur in summaries (for example when
        // a count is stored in a field). Only reference inputs carry ownership.
        CallableSymbol callable = callables.get(target);
        EscapeSummary summary = summary(target);
        if (callable == null || summary == null
                || !callable.isStatic() && summary.thisEscapes()) { return false; }
        for (int index = 0; index < callable.parameterTypes().size(); index++) {
            if (callable.parameterTypes().get(index).isReference()
                    && summary.parameterEscapes(index)) { return false; }
        }
        return true;
    }

    private boolean isNonRetainingPrimitiveDispatch(TypeSymbol receiver, String name,
                                                     int arity, boolean staticCall) {
        if (receiver == null) { return false; }
        boolean found = false;
        for (TypeSymbol actual : types.values()) {
            if (staticCall ? actual != receiver : actual.isInterface() || !isSubtype(actual, receiver)) {
                continue;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (TypeSymbol current = actual; current != null; current = current.superclass().orElse(null)) {
                var methods = current.declaredMethodsNamed(name);
                for (CallableSymbol method : methods) {
                    if (method.isStatic() != staticCall
                            || method.parameters().size() != arity) { continue; }
                    if (!seen.add(method.erasedSignatureKey())) { continue; }
                    if (method.body().isEmpty()) { continue; }
                    if (method.returnType().isReference()) { return false; }
                    EscapeSummary summary = summary(method);
                    if (!staticCall && summary.thisEscapes()) { return false; }
                    for (int index = 0; index < method.parameters().size(); index++) {
                        if (method.parameterTypes().get(index).isReference()
                                && summary.parameterEscapes(index)) { return false; }
                    }
                    found = true;
                }
            }
            if (!staticCall) {
                // A concrete class can inherit an interface body without declaring
                // a method. Join all default bodies conservatively, including when
                // another implementation overrides that default with a safe body.
                for (TypeSymbol contract : actual.interfaceClosure()) {
                    for (CallableSymbol method : contract.declaredMethodsNamed(name)) {
                        if (method.isStatic() || method.parameters().size() != arity
                                || method.body().isEmpty()) { continue; }
                        if (method.returnType().isReference()) { return false; }
                        EscapeSummary summary = summary(method);
                        if (summary.thisEscapes()) { return false; }
                        for (int index = 0; index < method.parameters().size(); index++) {
                            if (method.parameterTypes().get(index).isReference()
                                    && summary.parameterEscapes(index)) { return false; }
                        }
                        found = true;
                    }
                }
            }
        }
        return found;
    }

    private CallableSymbol resolveCall(CallExpression call,
                                       Map<String, Set<Integer>> environment,
                                       boolean staticFunction) {
        TypeSymbol receiverType;
        Boolean requireStatic;
        if (call.receiver().isEmpty()) {
            receiverType = analyzingOwner;
            requireStatic = staticFunction ? Boolean.TRUE : null;
        } else {
            Expression receiver = call.receiver().orElseThrow();
            String typeName = qualifiedName(receiver);
            TypeSymbol staticType = typeName == null || environment.containsKey(typeName)
                    ? null : resolver.resolve(typeName, analyzingOwner, receiver.span())
                    .type().orElse(null);
            if (staticType != null) {
                receiverType = staticType;
                requireStatic = Boolean.TRUE;
            } else {
                receiverType = receiverType(receiver, environment);
                requireStatic = Boolean.FALSE;
            }
        }
        if (receiverType == null) {
            return null;
        }
        for (TypeSymbol current = receiverType; current != null;
             current = current.superclass().orElse(null)) {
            java.util.List<CallableSymbol> candidates = current
                    .declaredMethodsNamed(call.methodName()).stream()
                    .filter(method -> requireStatic == null
                            || method.isStatic() == requireStatic)
                    .filter(method -> method.parameters().size() == call.arguments().size())
                    .toList();
            if (!candidates.isEmpty()) {
                return candidates.size() == 1 && directlyBound(candidates.getFirst())
                        ? candidates.getFirst() : null;
            }
        }
        return null;
    }

    private boolean directlyBound(CallableSymbol method) {
        TypeSymbol declaringType = types.get(method.ownerType());
        return method.isStatic() || method.isFinal()
                || method.accessModifier()
                == ironwood.compiler.ast.AccessModifier.PRIVATE
                || declaringType != null && !declaringType.isInterface()
                && (declaringType.isFinal()
                || !hasClosedWorldOverride(declaringType, method));
    }

    private boolean hasClosedWorldOverride(TypeSymbol declaringType,
                                           CallableSymbol method) {
        for (TypeSymbol candidate : types.values()) {
            if (candidate == declaringType || candidate.isInterface()
                    || !isSubtype(candidate, declaringType)) {
                continue;
            }
            boolean overrides = candidate.declaredMethodsNamed(method.sourceName()).stream()
                    .anyMatch(other -> !other.isStatic()
                            && other.erasedSignatureKey().equals(
                            method.erasedSignatureKey()));
            if (overrides) {
                return true;
            }
        }
        return false;
    }

    private TypeSymbol receiverType(Expression receiver,
                                    Map<String, Set<Integer>> environment) {
        if (receiver instanceof ThisExpression || receiver instanceof SuperExpression) {
            return analyzingOwner;
        }
        if (receiver instanceof NameExpression local && environment.containsKey(local.name())) {
            for (int index = 0; index < analyzingCallable.parameters().size(); index++) {
                if (!analyzingCallable.parameters().get(index).name().equals(local.name())
                        || index >= analyzingCallable.parameterTypes().size()) {
                    continue;
                }
                IrType type = analyzingCallable.parameterTypes().get(index).erasure();
                return type.isNominalReference() ? types.get(type.referenceName()) : null;
            }
            return null;
        }
        String fieldName = null;
        if (receiver instanceof NameExpression name && !environment.containsKey(name.name())) {
            fieldName = name.name();
        } else if (receiver instanceof FieldAccessExpression access
                && access.receiver() instanceof ThisExpression) {
            fieldName = access.fieldName();
        }
        if (fieldName == null) {
            return null;
        }
        for (TypeSymbol current = analyzingOwner; current != null;
             current = current.superclass().orElse(null)) {
            FieldSymbol field = current.declaredFields().get(fieldName);
            if (field != null) {
                return field.type().isNominalReference()
                        ? types.get(field.type().referenceName()) : null;
            }
        }
        return null;
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

    private boolean capturesImplicitEnclosingInstance(NewExpression allocation) {
        if (analyzingOwner == null) {
            return false;
        }
        TypeSymbol allocated = allocatedType(allocation);
        if (allocated == null || !allocated.isInnerClass()) {
            return false;
        }
        TypeSymbol required = allocated.enclosingType().orElse(null);
        for (TypeSymbol current = analyzingOwner; current != null;
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

    private TypeSymbol allocatedType(NewExpression allocation) {
        if (analyzingOwner == null) {
            return null;
        }
        TypeSymbol anonymous = types.values().stream()
                .filter(TypeSymbol::isAnonymousClass)
                .filter(type -> type.anonymousAllocation().orElse(null) == allocation)
                .findFirst().orElse(null);
        if (anonymous != null) {
            return anonymous;
        }
        return resolver.resolve(allocation.className(), analyzingOwner, allocation.classNameSpan())
                .type().orElse(null);
    }

    private static boolean isSubtype(TypeSymbol actual, TypeSymbol expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (expected.isInterface() && actual.interfaceClosure().contains(expected)) { return true; }
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

    private static void markEnvironmentEscaped(Map<String, Set<Integer>> environment,
                                               Set<Integer> escaped) {
        environment.values().forEach(value -> markEscaped(value, escaped));
    }

    private static void markEscaped(Set<Integer> origins, Set<Integer> escaped) {
        escaped.addAll(origins);
    }

    private static Map<String, Set<Integer>> copy(Map<String, Set<Integer>> environment) {
        Map<String, Set<Integer>> copied = new LinkedHashMap<>();
        environment.forEach((name, origins) -> copied.put(name, Set.copyOf(origins)));
        return copied;
    }

    @SafeVarargs
    private static void mergeExisting(Map<String, Set<Integer>> target,
                                      Map<String, Set<Integer>>... incoming) {
        for (String name : Set.copyOf(target.keySet())) {
            Set<Integer> merged = new LinkedHashSet<>();
            for (Map<String, Set<Integer>> environment : incoming) {
                merged.addAll(environment.getOrDefault(name, Set.of()));
            }
            target.put(name, Set.copyOf(merged));
        }
    }

    record EscapeSummary(boolean thisEscapes, Set<Integer> escapingParameters,
                         Set<Integer> receiverRetainedParameters,
                         Set<ReturnOrigin> returnedOrigins,
                         Set<SymbolicReturnOriginAnalyzer.BorrowedReturnOrigin>
                                 borrowedReturnedOrigins,
                         boolean mayReturnNonOrigin,
                         boolean mayReturnFresh,
                         boolean mayReturnNull,
                         boolean freshEscapes,
                         boolean thisEscapesWithoutReturn,
                         Set<Integer> parametersEscapingWithoutReturn) {
        EscapeSummary {
            escapingParameters = Set.copyOf(escapingParameters);
            receiverRetainedParameters = Set.copyOf(receiverRetainedParameters);
            returnedOrigins = Collections.unmodifiableSet(
                    new LinkedHashSet<>(returnedOrigins));
            borrowedReturnedOrigins = Collections.unmodifiableSet(
                    new LinkedHashSet<>(borrowedReturnedOrigins));
            parametersEscapingWithoutReturn = Set.copyOf(parametersEscapingWithoutReturn);
        }

        EscapeSummary(boolean thisEscapes, Set<Integer> escapingParameters,
                      Set<Integer> receiverRetainedParameters) {
            this(thisEscapes, escapingParameters, receiverRetainedParameters,
                    Set.of(), Set.of(), true, false, false, true,
                    thisEscapes, escapingParameters);
        }

        boolean parameterEscapes(int index) {
            return escapingParameters.contains(index)
                    || receiverRetainedParameters.contains(index);
        }

        boolean parameterEscapesOutsideReceiver(int index) {
            return escapingParameters.contains(index);
        }

        boolean parameterRetainedByReceiverOnly(int index) {
            return receiverRetainedParameters.contains(index)
                    && !escapingParameters.contains(index);
        }

        boolean parameterEscapesWithoutReturn(int index) {
            return parametersEscapingWithoutReturn.contains(index)
                    || receiverRetainedParameters.contains(index);
        }

        EscapeSummary withBorrowingContract(boolean borrowReceiver,
                                            boolean borrowParameters) {
            return new EscapeSummary(
                    borrowReceiver ? false : thisEscapes,
                    borrowParameters ? Set.of() : escapingParameters,
                    borrowParameters ? Set.of() : receiverRetainedParameters,
                    returnedOrigins, borrowedReturnedOrigins, mayReturnNonOrigin,
                    mayReturnFresh, mayReturnNull, freshEscapes,
                    borrowReceiver ? false : thisEscapesWithoutReturn,
                    borrowParameters ? Set.of() : parametersEscapingWithoutReturn);
        }

        EscapeSummary withSymbolicReturnSummary(
                SymbolicReturnOriginAnalyzer.ReturnSummary symbolic) {
            boolean symbolicThisEscapes = symbolic.nonReturnEscapingOrigins().stream()
                    .anyMatch(origin -> origin.kind() == ReturnOrigin.Kind.THIS);
            Set<Integer> symbolicParameters = symbolic.nonReturnEscapingOrigins().stream()
                    .filter(origin -> origin.kind() == ReturnOrigin.Kind.PARAMETER)
                    .map(ReturnOrigin::parameterIndex)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            boolean borrowedThisEscapes = symbolic.borrowedReturnedOrigins().stream()
                    .anyMatch(origin -> origin.ownerOrigin().kind()
                            == ReturnOrigin.Kind.THIS);
            Set<Integer> allEscapingParameters = new LinkedHashSet<>(escapingParameters);
            symbolic.borrowedReturnedOrigins().stream()
                    .map(SymbolicReturnOriginAnalyzer.BorrowedReturnOrigin::ownerOrigin)
                    .filter(origin -> origin.kind() == ReturnOrigin.Kind.PARAMETER)
                    .map(ReturnOrigin::parameterIndex)
                    .forEach(allEscapingParameters::add);
            return new EscapeSummary(thisEscapes || borrowedThisEscapes,
                    allEscapingParameters,
                    receiverRetainedParameters,
                    symbolic.returnedOrigins(), symbolic.borrowedReturnedOrigins(),
                    symbolic.mayReturnNonOrigin(),
                    symbolic.mayReturnFresh(), symbolic.mayReturnNull(),
                    symbolic.freshEscapes(),
                    symbolicThisEscapes, symbolicParameters);
        }

        boolean returnsOwnedFresh() {
            return mayReturnFresh && !mayReturnNonOrigin && !freshEscapes
                    && returnedOrigins.isEmpty() && borrowedReturnedOrigins.isEmpty();
        }

        static EscapeSummary unknown(CallableSymbol callable) {
            Set<Integer> parameters = new LinkedHashSet<>();
            for (int index = 0; index < callable.parameters().size(); index++) {
                parameters.add(index);
            }
            return new EscapeSummary(!callable.isStatic(), parameters, Set.of());
        }
    }
}
