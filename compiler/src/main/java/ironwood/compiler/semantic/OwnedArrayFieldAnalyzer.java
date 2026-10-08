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
import ironwood.compiler.ast.BreakStatement;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.ContinueStatement;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EmptyStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.EnumConstantInitialization;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.DeferStatement;
import ironwood.compiler.ast.DeferredFreeStatement;
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
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

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
    record Failure(String detail, SourceFile source, SourceSpan span,
                   RejectedFreeEvidence.Call call) {
    }

    private final Map<String, TypeSymbol> types;
    private final ClassHierarchy hierarchy;
    private final EscapeSummaryAnalyzer escapeSummaries;
    private final ReentrantOperations reentrant;
    private final long observerToken;
    private final Set<String> ownedFields = new LinkedHashSet<>();
    private final Map<String, FieldSymbol> borrowedReturnFields = new LinkedHashMap<>();
    private final Set<String> ambiguousBorrowedReturns = new LinkedHashSet<>();
    private final Map<String, String> rejectionReasons = new LinkedHashMap<>();
    private final RejectedFreeEvidence.Budget evidenceBudget;
    private final Map<String, Failure> failures;
    private int failureUnits;
    private final Map<String, Boolean> encapsulatedFields = new LinkedHashMap<>();
    /** Frees an owned field's proof left to lowering, which must reject each (D297). */
    private final Map<ContingentFree, String> contingentFrees = new LinkedHashMap<>();
    private final Map<String, Boolean> confinedCleanupFields = new LinkedHashMap<>();

    OwnedArrayFieldAnalyzer(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                            EscapeSummaryAnalyzer escapeSummaries) {
        this(types, hierarchy, escapeSummaries, null, 0,
                SemanticAnalysisObserver.AnalyzerPhase.INITIAL);
    }

    OwnedArrayFieldAnalyzer(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                            EscapeSummaryAnalyzer escapeSummaries,
                            SemanticAnalysisObserver observer, long observerToken,
                            SemanticAnalysisObserver.AnalyzerPhase phase) {
        this(types, hierarchy, escapeSummaries, observer, observerToken, phase, null);
    }

    OwnedArrayFieldAnalyzer(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                            EscapeSummaryAnalyzer escapeSummaries,
                            SemanticAnalysisObserver observer, long observerToken,
                            SemanticAnalysisObserver.AnalyzerPhase phase,
                            RejectedFreeEvidence.Budget evidenceBudget) {
        this(types, hierarchy, escapeSummaries, observer, observerToken, phase, evidenceBudget,
                ReentrantOperations.NONE);
    }

    OwnedArrayFieldAnalyzer(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                            EscapeSummaryAnalyzer escapeSummaries,
                            SemanticAnalysisObserver observer, long observerToken,
                            SemanticAnalysisObserver.AnalyzerPhase phase,
                            RejectedFreeEvidence.Budget evidenceBudget,
                            ReentrantOperations reentrant) {
        this.reentrant = reentrant;
        this.observerToken = observerToken;
        if (observer != null) {
            observer.analyzerCreated(observerToken,
                    SemanticAnalysisObserver.AnalyzerKind.OWNED_FIELD, phase);
        }
        this.types = types;
        this.hierarchy = hierarchy;
        this.escapeSummaries = escapeSummaries;
        this.evidenceBudget = evidenceBudget;
        this.failures = evidenceBudget == null ? null : new LinkedHashMap<>();
        if (observer != null) {
            observer.fieldEvidenceLifecycle(observerToken, failures != null, false);
        }
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
                        field.type().isNominalReference() || auditedByteBufferArrayLoan, true);
                if (!field.isStatic()
                        && field.accessModifier() == ironwood.compiler.ast.AccessModifier.PRIVATE
                        && field.type().isReference()
                        && checker.isOwned()) {
                    ownedFields.add(key(field));
                    checker.contingent.forEach(contingentFrees::putIfAbsent);
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

    long observerToken() {
        return observerToken;
    }

    Failure failure(FieldSymbol field) {
        return failures == null ? null : failures.get(key(field));
    }

    void retireFailureEvidence(SemanticAnalysisObserver observer) {
        if (failures == null) return;
        if (observer != null) observer.fieldEvidenceFinished(observerToken,
                failures.size(), failureUnits);
        failures.clear();
        evidenceBudget.release(failureUnits);
        failureUnits = 0;
        if (observer != null) observer.fieldEvidenceLifecycle(observerToken, true, true);
    }

    Map<String, String> observerProjection() {
        Map<String, String> facts = new java.util.TreeMap<>();
        ownedFields.forEach(name -> facts.put(name, "owned"));
        borrowedReturnFields.forEach((name, field) -> facts.put("borrow:" + name,
                field.irField().name()));
        rejectionReasons.forEach((name, reason) -> facts.put("rejected:" + name, reason));
        return Map.copyOf(facts);
    }

    boolean isOwned(FieldSymbol field) {
        return ownedFields.contains(key(field));
    }

    /**
     * When an owned field's proof assumed that the free statement at {@code span} of
     * {@code source} never runs, the live alias and field that free would cross, as
     * {@code local 'k' aliases field 'held'}; lowering must reject that free (D297).
     */
    String contingentFree(SourceFile source, SourceSpan span) {
        return contingentFrees.get(new ContingentFree(sourcePath(source), span));
    }

    boolean isContainedListViewAssignment(CallableSymbol callable, FieldSymbol target, Expression expression) {
        if (callable == null || !callable.isConstructor() || target == null || !isOwned(target)
                || !(expression instanceof CallExpression call) || call.arguments().size() != 1
                || call.receiver().isPresent() && !(call.receiver().orElseThrow() instanceof NameExpression)
                || !(call.arguments().getFirst() instanceof FieldAccessExpression access)
                || !(access.receiver() instanceof ThisExpression)) return false;
        TypeSymbol owner = types.get(callable.ownerType());
        FieldSymbol backing = owner == null ? null : owner.declaredFields().get(access.fieldName());
        java.util.List<CallableSymbol> targets = escapeSummaries.boundTargets(callable, call);
        return backing != null && isOwned(backing) && targets.size() == 1
                && DataStructureSemantics.isListViewFactory(targets.getFirst())
                && escapeSummaries.summary(targets.getFirst()).returnsOwnedFresh();
    }

    boolean sameProofsAs(OwnedArrayFieldAnalyzer other) {
        return ownedFields.equals(other.ownedFields)
                && contingentFrees.equals(other.contingentFrees)
                && borrowedReturnFields.equals(other.borrowedReturnFields)
                && ambiguousBorrowedReturns.equals(other.ambiguousBorrowedReturns);
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

    boolean cleanupPreservesBorrow(FieldSymbol field) {
        return confinedCleanupFields.computeIfAbsent(key(field), ignored -> checkCleanupBorrow(field));
    }

    private boolean checkCleanupBorrow(FieldSymbol field) {
        TypeSymbol owner = types.get(field.ownerClass());
        if (owner == null) return false;
        Checker checker = new Checker(owner, field, false, false);
        // Ordinary encapsulation scans constructors and methods. A temporary
        // borrower also needs its destruction to end, rather than publish, the
        // loan. Nestmate cleanup can access the same private field.
        for (TypeSymbol candidate : types.values()) {
            if (candidate == owner || owner.sameNest(candidate)) {
                candidate.destructor().ifPresent(checker::scanCallable);
            }
        }
        return checker.owned;
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

    /** Lowering places instance initializers in every constructor of their class. */
    private static Set<String> constructorFunctions(TypeSymbol type) {
        Set<String> functions = new LinkedHashSet<>();
        type.constructors().forEach(constructor -> functions.add(constructor.linkageName()));
        return functions;
    }

    /**
     * The expressions of a block statement that run, whenever control passes it, before
     * its nested statements and before the block's later statements (D304). A do-while
     * condition runs after its body, and a for loop's initializer and updates are not
     * searched.
     */
    private static java.util.List<Expression> headExpressions(Statement statement) {
        if (statement instanceof ExpressionStatement expression) {
            return java.util.List.of(expression.expression());
        }
        if (statement instanceof LocalVariableDeclaration local) {
            return java.util.List.of(local.initializer());
        }
        if (statement instanceof AssignmentStatement assignment) {
            return java.util.List.of(assignment.target(), assignment.value());
        }
        if (statement instanceof IfStatement conditional) {
            return java.util.List.of(conditional.condition());
        }
        if (statement instanceof WhileStatement loop) {
            return java.util.List.of(loop.condition());
        }
        if (statement instanceof ForStatement loop) {
            return loop.condition().map(java.util.List::of).orElse(java.util.List.of());
        }
        if (statement instanceof EnhancedForStatement loop) {
            return java.util.List.of(loop.iterable());
        }
        if (statement instanceof SwitchStatement switched) {
            return java.util.List.of(switched.selector());
        }
        if (statement instanceof ModernSwitchStatement switched) {
            return java.util.List.of(switched.selector());
        }
        return java.util.List.of();
    }

    /**
     * The operands that evaluating {@code expression} always evaluates, in source order,
     * which Java's left-to-right evaluation follows (D304): not the right operand of
     * {@code &&} or {@code ||}, a conditional's branches, a switch expression's arms or
     * an anonymous class body.
     */
    private static java.util.List<Expression> evaluatedOperands(Expression expression) {
        java.util.List<Expression> operands = new java.util.ArrayList<>();
        if (expression instanceof AssignmentExpression assignment) {
            operands.add(assignment.target());
            operands.add(assignment.value());
        } else if (expression instanceof BinaryExpression binary) {
            operands.add(binary.left());
            if (binary.operator() != BinaryOperator.LOGICAL_AND
                    && binary.operator() != BinaryOperator.LOGICAL_OR) {
                operands.add(binary.right());
            }
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
        } else if (expression instanceof FieldAccessExpression access) {
            operands.add(access.receiver());
        } else if (expression instanceof CastExpression cast) {
            operands.add(cast.operand());
        } else if (expression instanceof UnaryExpression unary) {
            operands.add(unary.operand());
        } else if (expression instanceof InstanceOfExpression test) {
            operands.add(test.operand());
        } else if (expression instanceof UpdateExpression update) {
            operands.add(update.target());
        } else if (expression instanceof ConditionalExpression conditional) {
            operands.add(conditional.condition());
        } else if (expression instanceof SwitchExpression switched) {
            operands.add(switched.selector());
        }
        return operands;
    }

    /** A scope's deferred actions and the locals declared outside it. */
    private record DeferredScope(Set<String> outer, java.util.List<Expression> actions) {
    }

    /**
     * A free or deferred free of a local, seen by a witness probe (D298, D301), and the
     * code until it frees: the statement itself, or a deferred free's block (D303).
     */
    private record FreedLocal(String name, SourceSpan span, SourceSpan reach) {
    }

    /**
     * A write to a local, by name and target span, seen by a witness probe, with what it
     * writes, or null when that is not a {@link LoadSource} (D303, D306).
     */
    private record LocalWrite(String name, SourceSpan span, LoadSource value) {
    }

    /**
     * A declaration or assignment of a local that runs whenever control passes its block
     * statement, with what it writes; that block; and the block's statements from that
     * one on (D299, D303, D304, D306).
     */
    private record FieldLoad(String name, SourceSpan declaration, SourceSpan scope,
                             java.util.List<SourceSpan> following, LoadSource value) {
    }

    /**
     * A value that holds a load of the field through {@code this} when its parts do
     * (D306): such a load, possibly under casts, a local's value where the expression
     * reads it, or the branch a conditional selects.
     */
    private sealed interface LoadSource permits FieldValue, LocalValue, EitherValue {
    }

    private record FieldValue() implements LoadSource {
    }

    private record LocalValue(String name, int position) implements LoadSource {
    }

    private record EitherValue(LoadSource first, LoadSource second) implements LoadSource {
    }

    /** Whether a local holds a field load where it is read or freed, up to {@code reach} (D306). */
    private record HoldingQuery(String name, int position, int reach) {
    }

    /**
     * A free statement by source path and span: it is enforced in every function the
     * statement is lowered into, such as the constructors an initializer joins.
     */
    private record ContingentFree(String source, SourceSpan span) {
    }

    private static String sourcePath(SourceFile source) {
        return source == null || source.path() == null ? "" : source.path().toString();
    }

    private final class Checker {
        private final TypeSymbol owner;
        private final FieldSymbol candidate;
        private final boolean requireFreshWrites;
        private final boolean allowBorrowedReturns;
        private boolean owned = true;
        private final boolean collectFailure;
        /** Whether a free that may run code can be left to lowering (D297). */
        private final boolean allowContingentFrees;
        private final Map<ContingentFree, String> contingent = new LinkedHashMap<>();
        /** Whether the program needs the candidate owned (D297, D298); computed on demand. */
        private Boolean ownershipNeeded;
        /** The writes to locals a witness probe saw, or null outside a probe (D298, D303). */
        private java.util.List<LocalWrite> localWrites;
        /** The frees of locals a witness probe saw, or null outside a probe (D298, D301). */
        private java.util.List<FreedLocal> freedLocals;
        /** Whether a witness probe saw a free of the candidate field itself (D298). */
        private boolean freedCandidate;
        /** The field-load declarations a witness probe saw, or null outside a probe (D299). */
        private java.util.List<FieldLoad> fieldLoads;
        /** How many finally blocks lowering may never reach enclose the probe's scan (D302). */
        private int unreachedFinallies;
        private String rejectionReason;
        private boolean staticFunction;
        private CallableSymbol currentCallable;
        private SourceFile currentSource;
        /** The lowered functions that contain the code being scanned. */
        private Set<String> currentFunctions = Set.of();
        /** Per enclosing scope, the deferred actions that run when it exits. */
        private final Deque<DeferredScope> deferredScopes = new ArrayDeque<>();
        private FieldSymbol constructionTarget;
        private final Set<String> borrowedReturnMethods = new LinkedHashSet<>();
        private final Set<String> nonBorrowedReturnMethods = new LinkedHashSet<>();
        private final Deque<boolean[]> switchYieldOrigins = new ArrayDeque<>();

        private Checker(TypeSymbol owner, FieldSymbol candidate,
                        boolean requireFreshWrites, boolean allowBorrowedReturns) {
            this(owner, candidate, requireFreshWrites, allowBorrowedReturns, false);
        }

        private Checker(TypeSymbol owner, FieldSymbol candidate,
                        boolean requireFreshWrites, boolean allowBorrowedReturns,
                        boolean collectFailure) {
            this.owner = owner;
            this.candidate = candidate;
            this.requireFreshWrites = requireFreshWrites;
            this.allowBorrowedReturns = allowBorrowedReturns;
            this.collectFailure = collectFailure;
            // Only the ownership proof, with typed IR, defers a free to lowering;
            // provisional proofs and encapsulation keep rejecting.
            this.allowContingentFrees = collectFailure && reentrant != ReentrantOperations.NONE;
            this.currentSource = owner.source();
        }

        private boolean isOwned() {
            FieldDeclaration declaration = candidate.declaration();
            declaration.initializer().ifPresent(initializer -> {
                if (requireFreshWrites && !isFreshValue(initializer)) {
                    rejectAt("this field initializer is not a proved fresh allocation",
                            initializer);
                }
            });
            staticFunction = false;
            currentFunctions = constructorFunctions(owner);
            Map<String, Boolean> initializerEnvironment = new LinkedHashMap<>();
            for (InstanceInitialization initialization
                    : ((ironwood.compiler.ast.ClassDeclaration) owner.declaration())
                    .instanceInitializations()) {
                if (initialization instanceof FieldDeclaration field) {
                    if (field != declaration) {
                        field.initializer().ifPresent(expression -> {
                            if (origin(expression, initializerEnvironment)) {
                                rejectAt("this initializer publishes the field's allocation "
                                        + "through another field", expression);
                            }
                        });
                    }
                } else {
                    scanBlock((Block) initialization, initializerEnvironment, true);
                }
            }
            owner.constructors().forEach(this::scanCallable);
            owner.declaredMethods().values().forEach(this::scanCallable);
            // Static initialization and destruction run nest code too: either could
            // publish the field or reenter while holding an alias of it.
            owner.staticInitializer().ifPresent(this::scanCallable);
            owner.destructor().ifPresent(this::scanCallable);
            for (TypeSymbol nestMate : types.values()) {
                if (nestMate == owner || !owner.sameNest(nestMate)) {
                    continue;
                }
                if (!nestMate.isInterface()) {
                    scanNestMateInitializers(nestMate);
                    nestMate.constructors().forEach(this::scanCallable);
                    nestMate.destructor().ifPresent(this::scanCallable);
                }
                nestMate.declaredMethods().values().forEach(this::scanCallable);
                nestMate.staticInitializer().ifPresent(this::scanCallable);
            }
            borrowedReturnMethods.removeAll(nonBorrowedReturnMethods);
            return owned;
        }

        private void scanNestMateInitializers(TypeSymbol nestMate) {
            staticFunction = false;
            SourceFile previousSource = currentSource;
            currentSource = nestMate.source();
            currentFunctions = constructorFunctions(nestMate);
            Map<String, Boolean> environment = new LinkedHashMap<>();
            for (InstanceInitialization initialization
                    : ((ironwood.compiler.ast.ClassDeclaration) nestMate.declaration())
                    .instanceInitializations()) {
                if (initialization instanceof FieldDeclaration field) {
                    field.initializer().ifPresent(expression -> {
                        if (origin(expression, environment)) {
                            rejectAt("this nestmate initializer publishes the field's allocation",
                                    expression);
                        }
                    });
                } else {
                    scanBlock((Block) initialization, environment, true);
                }
            }
            currentSource = previousSource;
        }

        private void scanCallable(CallableSymbol callable) {
            CallableSymbol previousCallable = currentCallable;
            SourceFile previousSource = currentSource;
            Set<String> previousFunctions = currentFunctions;
            currentCallable = callable;
            currentSource = types.get(callable.ownerType()).source();
            currentFunctions = Set.of(callable.linkageName());
            Map<String, Boolean> environment = new LinkedHashMap<>();
            callable.parameters().forEach(parameter -> environment.put(parameter.name(), false));
            staticFunction = callable.isStatic();
            callable.superInvocation().ifPresent(invocation -> invocation.arguments().forEach(argument -> {
                if (origin(argument, environment)) {
                    rejectAt("this superclass constructor argument can publish the field's "
                            + "allocation", argument);
                }
            }));
            callable.thisInvocation().ifPresent(invocation -> invocation.arguments().forEach(argument -> {
                if (origin(argument, environment)) {
                    rejectAt("this delegating constructor argument can publish the field's "
                            + "allocation", argument);
                }
            }));
            callable.body().ifPresent(body -> scanBlock(body, environment, false));
            currentCallable = previousCallable;
            currentSource = previousSource;
            currentFunctions = previousFunctions;
        }

        private void scanBlock(Block block, Map<String, Boolean> environment, boolean scoped) {
            Set<String> existing = Set.copyOf(environment.keySet());
            deferredScopes.push(new DeferredScope(existing, new java.util.ArrayList<>()));
            java.util.List<Statement> statements = block.statements();
            for (int index = 0; index < statements.size(); index++) {
                Statement statement = statements.get(index);
                if (fieldLoads != null) {
                    recordFieldLoads(statement, environment, block.span(), statements
                            .subList(index, statements.size()).stream().map(Statement::span).toList());
                }
                // Lowering frees a deferred target only on a route out of the rest of
                // the block (D301, D302).
                if (freedLocals != null && unreachedFinallies == 0
                        && statement instanceof DeferredFreeStatement deferred
                        && LoweredRoutes.leaves(statements.subList(index + 1, statements.size()))) {
                    freedLocals.add(new FreedLocal(deferred.target().name(), statement.span(), block.span()));
                }
                scanStatement(statement, environment);
            }
            deferredScopes.pop();
            if (scoped) {
                environment.keySet().removeIf(name -> !existing.contains(name));
            }
        }

        private void scanStatement(Statement statement, Map<String, Boolean> environment) {
            scanStatementKind(statement, environment);
            // A deferred action runs when its scope exits, also early through an
            // exception. Locals of that scope are dead by then, but an alias held in
            // an outer local is live then and may be used afterwards.
            for (DeferredScope scope : deferredScopes) {
                if (!scope.actions().isEmpty() && scope.outer().stream()
                        .anyMatch(name -> Boolean.TRUE.equals(environment.get(name)))) {
                    rejectAt("this deferred action can run while a local alias of the "
                            + "field's allocation remains active", scope.actions().getFirst());
                    return;
                }
            }
        }

        /**
         * The live alias a free of a local would cross when the proof may leave that free
         * to lowering, or null (D297). The owner's own destructor frees the field, so a
         * proof failure would make that destructor's free an error and the program
         * invalid anyway; the program stays invalid when lowering rejects this free
         * instead, and the field's facts change no valid program.
         */
        private String contingentAlias(FreeStatement free, Map<String, Boolean> environment) {
            if (!allowContingentFrees || !(free.value() instanceof NameExpression local)
                    || !environment.containsKey(local.name()) || !ownershipNeeded()) {
                return null;
            }
            return environment.entrySet().stream().filter(Map.Entry::getValue)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
        }

        /**
         * Whether a free in the program is rejected whenever the candidate is not owned,
         * so that a failed proof makes the program invalid: the owner's destructor frees
         * the field (D297), or its instance code frees a local loaded from it (D298-D306).
         */
        private boolean ownershipNeeded() {
            if (ownershipNeeded == null) {
                ownershipNeeded = destructorFreesCandidate() || instanceCodeFreesFieldLoad();
            }
            return ownershipNeeded;
        }

        /**
         * Whether the owner's instance code (constructors, methods, destructor or instance
         * initializers) frees, where lowering is certain to lower the free, a local that
         * holds a load of the field through {@code this} there (D298-D306): a declaration
         * or an assignment that a block statement always evaluates first writes such a
         * load, possibly under casts, or a local or conditional that holds one, and so
         * does every later write to the local before the free. Lowering tracks no
         * allocation for {@code this}, so it proves such a free only from a detached owned
         * field: without ownership the loaded value stays attached or has no identity and
         * the free is rejected. Java forbids redeclaring the name while it is in scope, so
         * a later free of that name in the block refers to that local.
         */
        private boolean instanceCodeFreesFieldLoad() {
            String name = candidate.declaration().name();
            java.util.List<CallableSymbol> callables = new java.util.ArrayList<>(owner.constructors());
            callables.addAll(owner.declaredMethods().values());
            owner.destructor().ifPresent(callables::add);
            for (CallableSymbol callable : callables) {
                if (callable.isStatic() || callable.isAbstract() || callable.body().isEmpty()
                        || callable.parameters().stream().anyMatch(parameter -> parameter.name().equals(name))) {
                    continue;
                }
                Checker probe = probe();
                probe.scanCallable(callable);
                if (probe.freesFieldLoad()) return true;
            }
            // Instance initializers run in every constructor, in one scope chain.
            Checker probe = probe();
            probe.staticFunction = false;
            probe.currentFunctions = constructorFunctions(owner);
            Map<String, Boolean> environment = new LinkedHashMap<>();
            for (InstanceInitialization initialization
                    : ((ironwood.compiler.ast.ClassDeclaration) owner.declaration()).instanceInitializations()) {
                if (initialization instanceof Block block) probe.scanBlock(block, environment, true);
            }
            return probe.freesFieldLoad();
        }

        /** A checker that only records frees, writes to locals and loads (D298-D306). */
        private Checker probe() {
            Checker probe = new Checker(owner, candidate, false, false);
            probe.localWrites = new java.util.ArrayList<>();
            probe.freedLocals = new java.util.ArrayList<>();
            probe.fieldLoads = new java.util.ArrayList<>();
            return probe;
        }

        /** Whether this probe saw a free of a local that holds a field load there (D306). */
        private boolean freesFieldLoad() {
            java.util.Set<HoldingQuery> frees = new LinkedHashSet<>();
            for (FreedLocal freed : freedLocals) {
                frees.add(new HoldingQuery(freed.name(), freed.span().start().offset(),
                        freed.reach().end().offset()));
            }
            Set<HoldingQuery> holding = holdingQueries(frees);
            return frees.stream().anyMatch(holding::contains);
        }

        /**
         * The queries, among {@code frees} and the local reads that loads and writes write,
         * whose local holds a field load (D306): the greatest set in which each query has a
         * load of its local that dominates it, writes a holding value and is followed by
         * holding writes only, up to the end of the block statement that holds the query
         * or the query's reach. Execution then keeps every query in the set true: a read
         * sees the dominating load or a later write in that window, which runs after the
         * reads its value makes, so a value read inside its own window, as in
         * {@code old = old}, keeps the field load.
         */
        private Set<HoldingQuery> holdingQueries(java.util.Set<HoldingQuery> frees) {
            Set<HoldingQuery> holding = new LinkedHashSet<>(frees);
            for (FieldLoad load : fieldLoads) collectReads(load.value(), holding);
            for (LocalWrite write : localWrites) collectReads(write.value(), holding);
            boolean changed = true;
            while (changed) {
                changed = holding.removeIf(query -> !holds(query, holding));
            }
            return holding;
        }

        private static void collectReads(LoadSource source, Set<HoldingQuery> queries) {
            if (source instanceof LocalValue read) {
                queries.add(new HoldingQuery(read.name(), read.position(), -1));
            } else if (source instanceof EitherValue either) {
                collectReads(either.first(), queries);
                collectReads(either.second(), queries);
            }
        }

        private boolean holds(HoldingQuery query, Set<HoldingQuery> holding) {
            for (FieldLoad load : fieldLoads) {
                if (!load.name().equals(query.name())
                        || query.position() <= load.declaration().end().offset()
                        || query.position() >= load.scope().end().offset()
                        || !holds(load.value(), holding)) {
                    continue;
                }
                // A later write runs only after the query, which is reached again only by
                // passing the load.
                int limit = load.following().stream()
                        .filter(statement -> statement.start().offset() <= query.position()
                                && query.position() < statement.end().offset())
                        .mapToInt(statement -> statement.end().offset()).findFirst()
                        .orElse(load.scope().end().offset());
                int end = Math.max(limit, query.reach());
                if (localWrites.stream().filter(write -> write.name().equals(query.name())
                                && write.span().start().offset() > load.declaration().end().offset()
                                && write.span().start().offset() < end)
                        .allMatch(write -> write.value() != null && holds(write.value(), holding))) {
                    return true;
                }
            }
            return false;
        }

        private static boolean holds(LoadSource source, Set<HoldingQuery> holding) {
            if (source instanceof LocalValue read) {
                return holding.contains(new HoldingQuery(read.name(), read.position(), -1));
            }
            if (source instanceof EitherValue either) {
                return holds(either.first(), holding) && holds(either.second(), holding);
            }
            return source instanceof FieldValue;
        }

        /**
         * What {@code value} writes as a {@link LoadSource}, or null (D306): a load of the
         * candidate through {@code this}, a local, a conditional whose branches both are
         * such values, or an assignment of one, possibly under casts.
         */
        private LoadSource loadSource(Expression value, Map<String, Boolean> environment) {
            while (value instanceof CastExpression cast) {
                value = cast.operand();
            }
            if (isThisFieldLoad(value, environment)) {
                return new FieldValue();
            }
            if (value instanceof NameExpression local && environment.containsKey(local.name())) {
                return new LocalValue(local.name(), local.span().start().offset());
            }
            if (value instanceof ConditionalExpression conditional) {
                LoadSource first = loadSource(conditional.whenTrue(), environment);
                LoadSource second = loadSource(conditional.whenFalse(), environment);
                return first == null || second == null ? null : new EitherValue(first, second);
            }
            if (value instanceof AssignmentExpression assignment
                    && assignment.operator() == AssignmentOperator.ASSIGN) {
                return loadSource(assignment.value(), environment);
            }
            return null;
        }

        /**
         * Records the loads of a block statement: a declaration of a local, and each plain
         * {@code =} assignment to a local or parameter that the statement's head
         * expressions always evaluate before the statement's nested statements and the
         * block's later statements, when it writes a {@link LoadSource} (D303, D304, D306).
         */
        private void recordFieldLoads(Statement statement, Map<String, Boolean> environment,
                                      SourceSpan block, java.util.List<SourceSpan> holders) {
            if (statement instanceof LocalVariableDeclaration local) {
                recordLoad(local.name(), local.initializer(), statement.span(), environment, block, holders);
            }
            if (statement instanceof AssignmentStatement assignment
                    && assignment.target() instanceof NameExpression local
                    && environment.containsKey(local.name())) {
                recordLoad(local.name(), assignment.value(), statement.span(), environment, block, holders);
            }
            for (Expression head : headExpressions(statement)) {
                recordAssignedLoads(head, environment, block, holders);
            }
        }

        private void recordAssignedLoads(Expression expression, Map<String, Boolean> environment,
                                         SourceSpan block, java.util.List<SourceSpan> holders) {
            if (expression instanceof AssignmentExpression assignment
                    && assignment.operator() == AssignmentOperator.ASSIGN
                    && assignment.target() instanceof NameExpression local
                    && environment.containsKey(local.name())) {
                recordLoad(local.name(), assignment.value(), assignment.span(), environment, block, holders);
            }
            for (Expression operand : evaluatedOperands(expression)) {
                recordAssignedLoads(operand, environment, block, holders);
            }
        }

        private void recordLoad(String name, Expression value, SourceSpan span, Map<String, Boolean> environment,
                                SourceSpan block, java.util.List<SourceSpan> holders) {
            LoadSource source = loadSource(value, environment);
            if (source != null) {
                fieldLoads.add(new FieldLoad(name, span, block, holders, source));
            }
        }

        /**
         * Whether {@code initializer} reads the candidate through {@code this}, as lowering
         * resolves it, also under casts (D305): a reference cast creates no object, and
         * lowering gives its result the operand's allocation identity or none.
         */
        private boolean isThisFieldLoad(Expression initializer, Map<String, Boolean> environment) {
            String name = candidate.declaration().name();
            while (initializer instanceof CastExpression cast) {
                initializer = cast.operand();
            }
            return !staticFunction && initializer instanceof NameExpression value && value.name().equals(name)
                    && !environment.containsKey(name)
                    || initializer instanceof FieldAccessExpression access
                    && access.receiver() instanceof ThisExpression && access.fieldName().equals(name);
        }

        /**
         * Whether the owner's own destructor frees the candidate, at any depth, as lowering
         * resolves it: {@code this.name}, or {@code name} with no local of that name in
         * scope (D297, D298). A probe skips frees in finally blocks that lowering may
         * never reach (D302); lowering reaches every other such statement and rejects it
         * unless the field is owned.
         */
        private boolean destructorFreesCandidate() {
            CallableSymbol destructor = owner.destructor().orElse(null);
            if (destructor == null || destructor.body().isEmpty()) return false;
            Checker probe = probe();
            probe.scanCallable(destructor);
            return probe.freedCandidate;
        }

        private void scanStatementKind(Statement statement, Map<String, Boolean> environment) {
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
            if (statement instanceof DeferStatement deferred) {
                origin(deferred.call(), environment);
                deferredScopes.peek().actions().add(deferred.call());
                return;
            }
            if (statement instanceof DeferredFreeStatement deferred) {
                if (!origin(deferred.target(), environment)
                        && !reentrant.inertFree(currentFunctions, statement.span())) {
                    deferredScopes.peek().actions().add(deferred.target());
                }
                return;
            }
            if (statement instanceof ExpressionStatement expression) {
                origin(expression.expression(), environment);
                return;
            }
            if (statement instanceof FreeStatement free) {
                if (freedLocals != null && unreachedFinallies == 0) {
                    String name = candidate.declaration().name();
                    if (free.value() instanceof NameExpression local) {
                        freedLocals.add(new FreedLocal(local.name(), statement.span(), statement.span()));
                        freedCandidate |= local.name().equals(name) && !environment.containsKey(name);
                    }
                    freedCandidate |= free.value() instanceof FieldAccessExpression access
                            && access.receiver() instanceof ThisExpression && access.fieldName().equals(name);
                }
                // Freeing anything but a field alias may run a destructor.
                if (!origin(free.value(), environment) && environment.containsValue(true)
                        && !reentrant.inertFree(currentFunctions, statement.span())) {
                    String alias = contingentAlias(free, environment);
                    if (alias != null) {
                        contingent.putIfAbsent(new ContingentFree(sourcePath(currentSource), statement.span()),
                                "local '" + alias + "' aliases field '" + candidate.declaration().name() + "'");
                    } else {
                        rejectAt("this free can run a destructor while a local alias of the "
                                + "field's allocation remains active", free.value());
                    }
                }
                return;
            }
            if (statement instanceof ReturnStatement returned) {
                // A whole-body fresh-wrapper proof accounts for the field's
                // lifetime through the returned object's constructor borrow.
                // Check every other use normally, including publication.
                if (currentCallable != null && escapeSummaries != null
                        && escapeSummaries.freshBorrowingFactory(currentCallable, candidate) != null) {
                    nonBorrowedReturnMethods.add(currentCallable.linkageName());
                    // A witness probe still records writes to locals in the value (D303).
                    if (localWrites != null) returned.value().ifPresent(value -> origin(value, environment));
                    return;
                }
                returned.value().ifPresent(value -> {
                    if (origin(value, environment)) {
                        if (allowBorrowedReturns && !staticFunction && currentCallable != null
                                && isBorrowedReturnExpression(value)) {
                            borrowedReturnMethods.add(currentCallable.linkageName());
                        } else {
                            rejectAt("this return exposes the field's allocation without a "
                                    + "proved dependent-borrow contract", value);
                        }
                    } else if (currentCallable != null && !isNullReturnExpression(value)) {
                        nonBorrowedReturnMethods.add(currentCallable.linkageName());
                    }
                });
                return;
            }
            if (statement instanceof ThrowStatement thrown) {
                if (origin(thrown.value(), environment)) {
                    rejectAt("this throw publishes the field's allocation", thrown.value());
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
                iterate(environment, iteration -> {
                    origin(loop.condition(), iteration);
                    Map<String, Boolean> body = copy(iteration);
                    scanScopedStatement(loop.body(), body);
                    merge(iteration, iteration, body);
                });
                return;
            }
            if (statement instanceof DoWhileStatement loop) {
                iterate(environment, iteration -> {
                    Map<String, Boolean> body = copy(iteration);
                    scanScopedStatement(loop.body(), body);
                    origin(loop.condition(), body);
                    merge(iteration, iteration, body);
                });
                return;
            }
            if (statement instanceof ForStatement loop) {
                Map<String, Boolean> loopEnvironment = copy(environment);
                loop.initializer().ifPresent(initializer -> scanStatement(initializer, loopEnvironment));
                iterate(loopEnvironment, iteration -> {
                    loop.condition().ifPresent(condition -> origin(condition, iteration));
                    Map<String, Boolean> body = copy(iteration);
                    scanScopedStatement(loop.body(), body);
                    loop.updates().forEach(update -> origin(update, body));
                    merge(iteration, iteration, body);
                });
                merge(environment, environment, loopEnvironment);
                return;
            }
            if (statement instanceof EnhancedForStatement loop) {
                iterate(environment, iteration -> {
                    // The source is evaluated once, but its iterator runs in every pass.
                    origin(loop.iterable(), iteration);
                    Map<String, Boolean> body = copy(iteration);
                    body.put(loop.variableName(), false);
                    scanScopedStatement(loop.body(), body);
                    merge(iteration, iteration, body);
                });
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
            if (statement instanceof EnumConstantInitialization initialization) {
                // Inserted before any static initializer statement, so no local alias
                // exists yet; only an argument can publish the field's allocation.
                initialization.constant().arguments().forEach(argument -> {
                    if (origin(argument, environment)) {
                        rejectAt("this enum constant argument can publish the field's "
                                + "allocation", argument);
                    }
                });
                return;
            }
            TryStatement guarded = (TryStatement) statement;
            Map<String, Boolean> merged = copy(environment);
            Map<String, Boolean> body = copy(environment);
            scanBlock(guarded.body(), body, true);
            merge(merged, merged, body);
            // An exception can leave the body anywhere, with any alias it made live.
            Map<String, Boolean> thrown = copy(merged);
            guarded.catches().forEach(caught -> {
                Map<String, Boolean> caughtEnvironment = copy(thrown);
                caughtEnvironment.put(caught.variableName(), false);
                scanBlock(caught.body(), caughtEnvironment, true);
                merge(merged, merged, caughtEnvironment);
            });
            guarded.finallyBlock().ifPresent(cleanup -> {
                // Lowering lowers a finally block only on a route that reaches it, so a
                // probe records no free in one that it may never reach (D302).
                boolean unreached = freedLocals != null && !LoweredRoutes.reachesFinally(guarded);
                if (unreached) unreachedFinallies++;
                scanBlock(cleanup, merged, true);
                if (unreached) unreachedFinallies--;
            });
            environment.clear();
            environment.putAll(merged);
        }

        private void scanInvocationArguments(SuperConstructorInvocation invocation,
                                             Map<String, Boolean> environment) {
            invocation.enclosingInstance().ifPresent(enclosing -> {
                if (origin(enclosing, environment)) {
                    rejectAt("this superclass constructor receives the field's allocation "
                            + "as its enclosing instance", enclosing);
                }
            });
            invocation.arguments().forEach(argument -> {
                if (origin(argument, environment)) {
                    rejectAt("this superclass constructor argument can publish the field's "
                            + "allocation", argument);
                }
            });
        }

        private void scanInvocationArguments(ThisConstructorInvocation invocation,
                                             Map<String, Boolean> environment) {
            invocation.arguments().forEach(argument -> {
                if (origin(argument, environment)) {
                    rejectAt("this delegating constructor argument can publish the field's "
                            + "allocation", argument);
                }
            });
        }

        private void scanScopedStatement(Statement statement, Map<String, Boolean> environment) {
            Set<String> existing = Set.copyOf(environment.keySet());
            deferredScopes.push(new DeferredScope(existing, new java.util.ArrayList<>()));
            scanStatement(statement, environment);
            deferredScopes.pop();
            environment.keySet().removeIf(name -> !existing.contains(name));
        }

        /**
         * Scans loop iterations until the environment at the start of one stops gaining
         * aliases: a later iteration starts with those an earlier one left live, and its
         * condition, iteration and body run while they are. The environment becomes the
         * join of every iteration's start and end.
         */
        private void iterate(Map<String, Boolean> environment,
                             java.util.function.Consumer<Map<String, Boolean>> iteration) {
            while (true) {
                Map<String, Boolean> next = copy(environment);
                iteration.accept(next);
                merge(next, environment, next);
                if (next.equals(environment)) {
                    return;
                }
                environment.clear();
                environment.putAll(next);
            }
        }

        private boolean origin(Expression expression, Map<String, Boolean> environment) {
            boolean attached = originOf(expression, environment);
            // Typed IR also shows code that the source does not spell as a call, such
            // as string conversion, class initialization and enhanced-for iteration.
            // Exact System.arraycopy copies references and runs no other code.
            if (environment.containsValue(true)
                    && !(expression instanceof CallExpression call && isSystemArrayCopy(call, environment))
                    && reentrant.runsCodeWithin(currentFunctions, expression.span())) {
                rejectAt("this expression can run other code while a local alias of the "
                        + "field's allocation remains active", expression);
            }
            return attached;
        }

        private boolean originOf(Expression expression, Map<String, Boolean> environment) {
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
                    rejectAt("this element access has a reentrant index while the field's "
                            + "array is attached", access.index());
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
                    rejectAt("this construction occurs while a local alias of the field's "
                            + "allocation remains active", allocation);
                }
                allocation.enclosingInstance().ifPresent(enclosing -> {
                    if (origin(enclosing, environment)) {
                        rejectAt("this construction passes the field's allocation as an "
                                + "enclosing instance", enclosing);
                    }
                });
                CallableSymbol constructor = resolveConstructor(allocation, environment);
                for (int index = 0; index < allocation.arguments().size(); index++) {
                    Expression argument = allocation.arguments().get(index);
                    boolean attached = origin(argument, environment);
                    if (attached && (constructor == null
                            || escapeSummaries.summary(constructor).parameterEscapes(index))
                            && !escapeSummaries.isTemporaryBorrow(currentCallable, allocation.span())
                            && !isContainedEntryBuilderBorrow(constructor, index)
                            && !isContainedElementBorrow(constructor, index)) {
                        rejectAt("this constructor argument is not proved confined to the "
                                + "field's owner", argument);
                    }
                }
                return false;
            }
            if (expression instanceof QualifiedSuperConstructorExpression invocation) {
                if (origin(invocation.enclosingInstance(), environment)) {
                    rejectAt("this superclass construction receives the field's allocation "
                            + "as its enclosing instance", invocation.enclosingInstance());
                }
                invocation.arguments().forEach(argument -> {
                    if (origin(argument, environment)) {
                        rejectAt("this superclass constructor argument can publish the "
                                + "field's allocation", argument);
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
                    rejectAt("this call occurs while a local alias of the field's allocation "
                            + "remains active", call);
                }
                if (currentCallable != null
                        && escapeSummaries.isNonRetainingPrimitiveCall(owner, currentCallable,
                                call, environment.keySet())) {
                    return false;
                }
                java.util.List<CallableSymbol> bound = escapeSummaries.boundTargets(currentCallable, call);
                CallableSymbol target = !bound.isEmpty() ? bound.getFirst() : receiverAttached
                        ? resolveAttachedCall(call) : resolveStaticCall(call, environment);
                if (target == null) {
                    if (receiverAttached || attachedArguments.contains(true)) {
                        rejectAt("this unresolved call receives the field's allocation; "
                                + "its retaining effect is unknown", call);
                    }
                    return false;
                }
                EscapeSummaryAnalyzer.EscapeSummary summary = bound.isEmpty()
                        ? escapeSummaries.summary(target) : escapeSummaries.combinedSummary(bound);
                ReturnOrigin exactReturn = !summary.mayReturnNonOrigin()
                        && summary.borrowedReturnedOrigins().isEmpty()
                        && summary.returnedOrigins().size() == 1
                        ? summary.returnedOrigins().iterator().next() : null;
                // Dispatch may join an owner-dependent view with a shared
                // process value. Non-return effects still include every target;
                // the shared alternative does not publish the receiver.
                boolean borrowedReturn = !summary.mayReturnFresh()
                        && summary.returnedOrigins().isEmpty()
                        && !summary.borrowedReturnedOrigins().isEmpty();
                boolean preciseReturn = exactReturn != null || borrowedReturn || summary.returnsOwnedFresh();
                boolean entryPoolCall = DataStructureSemantics.isEntryPool(candidate)
                        && (PoolSemantics.isRelease(target) || PoolSemantics.isCheckout(target));
                boolean samePoolRelease = escapeSummaries.returnsToOriginatingPool(currentCallable, call.span());
                if (receiverAttached && !entryPoolCall && !samePoolRelease && (preciseReturn
                        ? summary.thisEscapesWithoutReturn()
                        : summary.thisEscapes() || summary.thisEscapesWithoutReturn())) {
                    rejectCall(call.receiver().orElseThrow(), bound, target, -1,
                            summary.thisEscapesWithoutReturn());
                }
                for (int index = 0; index < attachedArguments.size(); index++) {
                    if (samePoolRelease) continue;
                    if (attachedArguments.get(index) && bound.size() == 1
                            && isContainedListViewBorrow(target, index)) {
                        continue;
                    }
                    if (attachedArguments.get(index) && (preciseReturn
                            ? summary.parameterEscapesWithoutReturn(index)
                            : summary.parameterEscapes(index)
                            || summary.parameterEscapesWithoutReturn(index))) {
                        rejectCall(call.arguments().get(index), bound, target,
                                index, summary.parameterEscapesWithoutReturn(index));
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
                    rejectAt("this expression uses a reentrant right operand while the "
                            + "field's allocation is attached", binary.right());
                }
                origin(binary.right(), environment);
                return false;
            }
            if (expression instanceof AssignmentExpression assignment) {
                if (assignment.target() instanceof ArrayAccessExpression access
                        && origin(access.array(), environment)
                        && (containsReentrantExpression(access.index())
                        || containsReentrantExpression(assignment.value()))
                        && !candidate.equals(OwnedArrayElementAnalyzer.constructionField(owner, currentCallable,
                            assignment.target(), assignment.value()))) {
                    rejectAt("this array store has a reentrant index or value while the "
                            + "field's storage is attached", assignment.target());
                }
                boolean valueOrigin = assignmentOrigin(assignment.target(),
                        assignment.value(), environment);
                assignKnownOrigin(assignment.target(),
                        assignment.operator() == AssignmentOperator.ASSIGN ? assignment.value() : null,
                        valueOrigin, environment,
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

        private void assign(Expression target, Expression value, Map<String, Boolean> environment,
                            boolean fresh) {
            if (target instanceof ArrayAccessExpression access
                    && origin(access.array(), environment)
                    && (containsReentrantExpression(access.index())
                    || containsReentrantExpression(value))
                    && !candidate.equals(OwnedArrayElementAnalyzer.constructionField(owner, currentCallable, target, value))) {
                rejectAt("this array store has a reentrant index or value while the "
                        + "field's storage is attached", target);
            }
            assignKnownOrigin(target, value, assignmentOrigin(target, value, environment), environment, fresh);
        }

        private boolean assignmentOrigin(Expression target, Expression value,
                                         Map<String, Boolean> environment) {
            FieldSymbol previousTarget = constructionTarget;
            String sibling = privateSiblingFieldName(target);
            constructionTarget = sibling == null ? null : owner.declaredFields().get(sibling);
            if (constructionTarget == null) constructionTarget =
                    OwnedArrayElementAnalyzer.constructionField(owner, currentCallable, target, value);
            boolean valueOrigin = origin(value, environment);
            constructionTarget = previousTarget;
            return valueOrigin;
        }

        private boolean isContainedElementBorrow(CallableSymbol constructor, int index) {
            return constructor != null && constructionTarget != null && constructionTarget != candidate
                    && currentCallable != null && currentCallable.isConstructor()
                    && constructionTarget.isFinal() && OwnedArrayElementAnalyzer.fields(owner).contains(constructionTarget)
                    && !escapeSummaries.summary(constructor).thisEscapesWithoutReturn()
                    && escapeSummaries.constructorArgumentIsConfined(constructor, index)
                    && new Checker(owner, constructionTarget, true, false).isOwned();
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

        private boolean isContainedListViewBorrow(CallableSymbol factory, int index) {
            if (index != 0 || currentCallable == null || !currentCallable.isConstructor()
                    || constructionTarget == null || !constructionTarget.isFinal() || !candidate.isFinal()
                    || !DataStructureSemantics.isListViewFactory(factory)
                    || !escapeSummaries.summary(factory).returnsOwnedFresh()
                    || !constructionTarget.type().isNominalReference()
                    || !constructionTarget.type().referenceName().equals(factory.returnType().referenceName())) return false;
            if (candidate.irField().layoutIndex() >= constructionTarget.irField().layoutIndex()) return false;
            TypeSymbol view = types.get(factory.returnType().referenceName());
            if (view == null || view.constructors().size() != 1) return false;
            CallableSymbol constructor = view.constructors().getFirst();
            if (escapeSummaries.summary(constructor).thisEscapes()
                    || !escapeSummaries.constructorArgumentIsConfined(constructor, 0)) return false;
            // Both fields stay private. A straight-line destructor must destroy
            // the view before its backing list; every other use is still scanned.
            CallableSymbol destructor = owner.destructor().orElse(null);
            if (destructor == null || destructor.body().isEmpty()) return false;
            boolean releasedView = false;
            boolean releasedBacking = false;
            for (Statement statement : destructor.body().orElseThrow().statements()) {
                if (!(statement instanceof FreeStatement free)
                        || !(free.value() instanceof FieldAccessExpression field)
                        || !(field.receiver() instanceof ThisExpression)) return false;
                if (field.fieldName().equals(constructionTarget.declaration().name())) releasedView = true;
                if (field.fieldName().equals(candidate.declaration().name())) {
                    if (!releasedView) return false;
                    releasedBacking = true;
                }
            }
            return releasedBacking && new Checker(owner, constructionTarget, true, true).isOwned();
        }

        /** Records an assignment; {@code value} is null for a compound assignment. */
        private void assignKnownOrigin(Expression target, Expression value, boolean valueOrigin,
                                       Map<String, Boolean> environment, boolean fresh) {
            if (target instanceof NameExpression name && environment.containsKey(name.name())) {
                if (localWrites != null) localWrites.add(new LocalWrite(name.name(), target.span(),
                        value == null ? null : loadSource(value, environment)));
                environment.put(name.name(), valueOrigin);
                return;
            }
            if (isCandidateField(target)) {
                if (requireFreshWrites && !fresh) {
                    rejectAt("this assignment does not install a proved fresh allocation",
                            target);
                } else {
                    environment.replaceAll((name, attached) -> false);
                }
                return;
            }
            String sibling = privateSiblingFieldName(target);
            if (valueOrigin && sibling != null) {
                if (collectFailure && failures != null && owned) {
                    recordFailure("this assignment publishes the field's allocation through "
                            + "private field '" + sibling + "'", target);
                }
                reject("allocation escapes through field '" + sibling + "'");
                return;
            }
            if (valueOrigin) {
                rejectAt("this assignment publishes the field's allocation outside its "
                        + "owning field", target);
            }
            if (target instanceof FieldAccessExpression access) {
                if (access.fieldName().equals(candidate.declaration().name())) {
                    rejectAt("this assignment targets the same field on another receiver",
                            target);
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
            if (isCandidateField(expression) || isNullReturnExpression(expression)) {
                return true;
            }
            if (expression instanceof CastExpression cast) {
                return isBorrowedReturnExpression(cast.operand());
            }
            if (expression instanceof ConditionalExpression conditional) {
                // origin() already checked the condition and both branches for
                // publication. Null does not create another storage owner.
                return isBorrowedReturnExpression(conditional.whenTrue())
                        && isBorrowedReturnExpression(conditional.whenFalse());
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

        private boolean isNullReturnExpression(Expression expression) {
            if (expression instanceof NullLiteralExpression) return true;
            if (expression instanceof CastExpression cast) return isNullReturnExpression(cast.operand());
            return expression instanceof ConditionalExpression conditional
                    && isNullReturnExpression(conditional.whenTrue())
                    && isNullReturnExpression(conditional.whenFalse());
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

        private void rejectAt(String detail, Expression expression) {
            recordFailure(detail, expression, null);
            reject();
        }

        private void recordFailure(String detail, Expression expression) {
            recordFailure(detail, expression, null);
        }

        private void rejectCall(Expression expression,
                                java.util.List<CallableSymbol> bound, CallableSymbol target,
                                int role, boolean nonReturn) {
            if (collectFailure && failures != null && owned) {
                String detail = role == -1
                        ? "this call can retain the field's allocation through its receiver"
                        : "this call can retain the field's allocation through argument "
                        + (role + 1);
                RejectedFreeEvidence.Call selected = SummaryCallExplanation.selectCall(
                        escapeSummaries, bound.isEmpty() ? java.util.List.of(target) : bound,
                        role, nonReturn, bound.size() > 1 || bound.isEmpty()
                                && !target.isStatic());
                recordFailure(detail, expression, selected);
            }
            reject();
        }

        private void recordFailure(String detail, Expression expression,
                                   RejectedFreeEvidence.Call call) {
            if (!collectFailure || failures == null || !owned
                    || failures.containsKey(key(candidate))) return;
            int units = call == null ? 2 : 3;
            if (!evidenceBudget.reserve(units)) return;
            failures.put(key(candidate), new Failure(detail, currentSource,
                    expression.span(), call));
            failureUnits += units;
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
            java.util.List<CallableSymbol> bound = escapeSummaries.boundTargets(currentCallable, call);
            if (!bound.isEmpty()) {
                return escapeSummaries.combinedSummary(bound).returnsOwnedFresh();
            }
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
            java.util.List<CallableSymbol> bound = escapeSummaries.boundTargets(currentCallable, call);
            if (!bound.isEmpty()) { return bound.getFirst(); }
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
            java.util.List<CallableSymbol> bound = currentCallable == null || escapeSummaries == null
                    ? java.util.List.of() : escapeSummaries.boundTargets(currentCallable.linkageName(),
                        allocation.span(), allocated.simpleName());
            if (!bound.isEmpty()) {
                return bound.size() == 1 && bound.getFirst().isConstructor()
                        && bound.getFirst().ownerType().equals(allocated.name()) ? bound.getFirst() : null;
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
            if (expression instanceof CallExpression || expression instanceof NewExpression
                    || reentrant.runsCodeWithin(currentFunctions, expression.span())) {
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
