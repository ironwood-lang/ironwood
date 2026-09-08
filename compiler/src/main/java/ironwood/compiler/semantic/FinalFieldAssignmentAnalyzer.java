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
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EmptyStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.InstanceOfExpression;
import ironwood.compiler.ast.InstanceInitialization;
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.QualifiedThisExpression;
import ironwood.compiler.ast.QualifiedSuperConstructorExpression;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.SwitchExpression;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchRuleThrow;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.ThisExpression;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UpdateExpression;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative Java-style definite-assignment analysis for blank final instance fields. */
final class FinalFieldAssignmentAnalyzer {
    private final SourceFile source;
    private final TypeSymbol type;
    private final List<Diagnostic> diagnostics;
    private final List<FieldSymbol> finalFields;
    private final boolean staticMode;
    private final Deque<Set<String>> scopes = new ArrayDeque<>();
    private final Deque<List<Map<FieldSymbol, State>>> breakFlows = new ArrayDeque<>();
    private final Deque<List<Map<FieldSymbol, State>>> yieldFlows = new ArrayDeque<>();
    private final Deque<LabeledBreakFlows> labeledBreakFlows = new ArrayDeque<>();

    FinalFieldAssignmentAnalyzer(SourceFile source, TypeSymbol type, List<Diagnostic> diagnostics) {
        this(source, type, diagnostics, false);
    }

    FinalFieldAssignmentAnalyzer(SourceFile source, TypeSymbol type, List<Diagnostic> diagnostics,
                                 boolean staticMode) {
        this.source = source;
        this.type = type;
        this.diagnostics = diagnostics;
        this.staticMode = staticMode;
        this.finalFields = type.declaredFields().values().stream()
                .filter(field -> field.isStatic() == staticMode && field.isFinal())
                .filter(field -> !staticMode
                        || type.enumConstant(field.declaration().name()).isEmpty())
                .toList();
    }

    void analyze() {
        if (finalFields.isEmpty()) {
            return;
        }
        scopes.clear();
        enterScope();
        Map<FieldSymbol, State> initializedFields = initializerState();
        exitScope();
        if (staticMode) {
            requireAssigned(initializedFields, type.declaration().nameSpan());
            return;
        }
        for (CallableSymbol constructor : type.constructors()) {
            analyzeConstructor(constructor, initializedFields);
        }
    }

    private Map<FieldSymbol, State> initializerState() {
        Map<FieldSymbol, State> fields = new LinkedHashMap<>();
        for (FieldSymbol field : finalFields) {
            fields.put(field, State.UNASSIGNED);
        }
        List<?> initializations;
        if (staticMode) {
            initializations = type.declaration() instanceof ironwood.compiler.ast.ClassDeclaration declaration
                    ? declaration.staticInitializations()
                    : ((InterfaceDeclaration) type.declaration()).fields();
        } else {
            initializations = ((ironwood.compiler.ast.ClassDeclaration) type.declaration())
                    .instanceInitializations();
        }
        for (var initialization : initializations) {
            if (initialization instanceof FieldDeclaration fieldDeclaration) {
                if (fieldDeclaration.initializer().isEmpty()) {
                    continue;
                }
                FieldSymbol field = type.declaredFields().get(fieldDeclaration.name());
                scanExpression(fieldDeclaration.initializer().orElseThrow(), fields, false);
                if (field.isFinal()) {
                    if (fields.get(field) != State.UNASSIGNED) {
                        diagnostics.add(error(fieldDeclaration.initializer().orElseThrow().span(),
                                "final field '" + fieldDeclaration.name() + "' may only be assigned once"));
                    }
                    fields.put(field, State.ASSIGNED);
                }
                continue;
            }
            Flow flow = scanBlock((Block) initialization, fields, true, false);
            fields = flow.fields();
            for (Map<FieldSymbol, State> returned : flow.returns()) {
                fields = join(fields, returned);
            }
        }
        return fields;
    }

    private void analyzeConstructor(CallableSymbol constructor,
                                    Map<FieldSymbol, State> initializedFields) {
        scopes.clear();
        enterScope();
        constructor.parameters().forEach(parameter -> declare(parameter.name()));
        Map<FieldSymbol, State> initial = new LinkedHashMap<>();
        for (FieldSymbol field : finalFields) {
            initial.put(field, constructor.thisInvocation().isPresent()
                    ? State.ASSIGNED : initializedFields.get(field));
        }
        Flow flow = scanBlock(constructor.body().orElseThrow(), initial, false, false);
        if (flow.reachable()) {
            requireAssigned(flow.fields(), constructor.nameSpan());
        }
        flow.returns().forEach(fields -> requireAssigned(fields, constructor.nameSpan()));
        exitScope();
    }

    private Flow scanBlock(Block block, Map<FieldSymbol, State> incoming,
                           boolean scoped, boolean inLoop) {
        if (scoped) {
            enterScope();
        }
        Map<FieldSymbol, State> fields = copy(incoming);
        boolean reachable = true;
        List<Map<FieldSymbol, State>> returns = new ArrayList<>();
        List<Map<FieldSymbol, State>> throwsFlows = new ArrayList<>();
        for (Statement statement : block.statements()) {
            if (!reachable) {
                break;
            }
            Flow flow = scanStatement(statement, fields, inLoop);
            returns.addAll(flow.returns());
            throwsFlows.addAll(flow.throwsFlows());
            fields = flow.fields();
            reachable = flow.reachable();
        }
        if (scoped) {
            exitScope();
        }
        return new Flow(fields, reachable, returns, throwsFlows);
    }

    private Flow scanStatement(Statement statement, Map<FieldSymbol, State> incoming,
                               boolean inLoop) {
        Map<FieldSymbol, State> fields = copy(incoming);
        if (statement instanceof Block block) {
            return scanBlock(block, fields, true, inLoop);
        }
        if (statement instanceof LocalVariableDeclaration local) {
            scanExpression(local.initializer(), fields, inLoop);
            declare(local.name());
            return new Flow(fields, true);
        }
        if (statement instanceof AssignmentStatement assignment) {
            scanExpression(assignment.value(), fields, inLoop);
            assign(assignment.target(), AssignmentOperator.ASSIGN, assignment.equalsSpan(), fields, inLoop);
            return new Flow(fields, true);
        }
        if (statement instanceof ExpressionStatement expression) {
            scanExpression(expression.expression(), fields, inLoop);
            return new Flow(fields, true);
        }
        if (statement instanceof FreeStatement free) {
            scanExpression(free.value(), fields, inLoop);
            return new Flow(fields, true);
        }
        if (statement instanceof ReturnStatement returned) {
            returned.value().ifPresent(value -> scanExpression(value, fields, inLoop));
            return new Flow(fields, false, List.of(copy(fields)), List.of());
        }
        if (statement instanceof ThrowStatement thrown) {
            scanExpression(thrown.value(), fields, inLoop);
            return new Flow(fields, false, List.of(), List.of(copy(fields)));
        }
        if (statement instanceof YieldStatement yielded) {
            scanExpression(yielded.value(), fields, inLoop);
            if (!yieldFlows.isEmpty()) {
                yieldFlows.peek().add(copy(fields));
            }
            return new Flow(fields, false);
        }
        if (statement instanceof IfStatement conditional) {
            scanExpression(conditional.condition(), fields, inLoop);
            Flow whenTrue = scanScoped(conditional.thenBranch(), fields, inLoop);
            Flow whenFalse = conditional.elseBranch()
                    .map(branch -> scanScoped(branch, fields, inLoop))
                    .orElse(new Flow(copy(fields), true));
            return mergeFlows(List.of(whenTrue, whenFalse));
        }
        if (statement instanceof WhileStatement loop) {
            Map<FieldSymbol, State> before = copy(fields);
            scanExpression(loop.condition(), fields, true);
            breakFlows.push(new ArrayList<>());
            Flow body = scanScoped(loop.body(), fields, true);
            breakFlows.pop();
            return new Flow(join(before, body.fields()), true,
                    body.returns(), body.throwsFlows());
        }
        if (statement instanceof DoWhileStatement loop) {
            breakFlows.push(new ArrayList<>());
            Flow body = scanScoped(loop.body(), fields, true);
            List<Map<FieldSymbol, State>> breaks = breakFlows.pop();
            if (body.reachable()) {
                scanExpression(loop.condition(), body.fields(), true);
            }
            List<Map<FieldSymbol, State>> normal = new ArrayList<>(breaks);
            if (body.reachable()) {
                normal.add(body.fields());
            }
            Map<FieldSymbol, State> result = normal.isEmpty()
                    ? fields : copy(normal.getFirst());
            for (int index = 1; index < normal.size(); index++) {
                result = join(result, normal.get(index));
            }
            return new Flow(result, !normal.isEmpty(), body.returns(), body.throwsFlows());
        }
        if (statement instanceof ForStatement loop) {
            enterScope();
            loop.initializer().ifPresent(initializer -> {
                Flow initialized = scanStatement(initializer, fields, false);
                fields.clear();
                fields.putAll(initialized.fields());
            });
            Map<FieldSymbol, State> before = copy(fields);
            loop.condition().ifPresent(condition -> scanExpression(condition, fields, true));
            breakFlows.push(new ArrayList<>());
            Flow body = scanScoped(loop.body(), fields, true);
            breakFlows.pop();
            loop.updates().forEach(update -> scanExpression(update, body.fields(), true));
            Map<FieldSymbol, State> result = join(before, body.fields());
            exitScope();
            return new Flow(result, true, body.returns(), body.throwsFlows());
        }
        if (statement instanceof EnhancedForStatement loop) {
            scanExpression(loop.iterable(), fields, true);
            Map<FieldSymbol, State> before = copy(fields);
            enterScope();
            declare(loop.variableName());
            breakFlows.push(new ArrayList<>());
            Flow body = scanScoped(loop.body(), fields, true);
            breakFlows.pop();
            exitScope();
            return new Flow(join(before, body.fields()), true,
                    body.returns(), body.throwsFlows());
        }
        if (statement instanceof LabeledStatement labeled) {
            LabeledBreakFlows context = new LabeledBreakFlows(labeled.label(), new ArrayList<>());
            labeledBreakFlows.push(context);
            Flow body = scanScoped(labeled.body(), fields, inLoop);
            labeledBreakFlows.pop();
            List<Map<FieldSymbol, State>> normal = new ArrayList<>(context.flows());
            if (body.reachable()) {
                normal.add(body.fields());
            }
            if (normal.isEmpty()) {
                return body;
            }
            Map<FieldSymbol, State> result = copy(normal.getFirst());
            for (int index = 1; index < normal.size(); index++) {
                result = join(result, normal.get(index));
            }
            return new Flow(result, true, body.returns(), body.throwsFlows());
        }
        if (statement instanceof EmptyStatement) {
            return new Flow(fields, true);
        }
        if (statement instanceof SwitchStatement switched) {
            scanExpression(switched.selector(), fields, inLoop);
            List<Map<FieldSymbol, State>> returns = new ArrayList<>();
            List<Map<FieldSymbol, State>> throwsFlows = new ArrayList<>();
            List<Map<FieldSymbol, State>> normal = new ArrayList<>();
            enterScope();
            List<Map<FieldSymbol, State>> switchBreaks = new ArrayList<>();
            breakFlows.push(switchBreaks);
            Flow fallthrough = null;
            for (var group : switched.groups()) {
                Map<FieldSymbol, State> groupFields = fallthrough != null && fallthrough.reachable()
                        ? join(fields, fallthrough.fields()) : copy(fields);
                boolean reachable = true;
                for (Statement child : group.statements()) {
                    if (!reachable) {
                        break;
                    }
                    Flow flow = scanStatement(child, groupFields, inLoop);
                    groupFields = flow.fields();
                    reachable = flow.reachable();
                    returns.addAll(flow.returns());
                    throwsFlows.addAll(flow.throwsFlows());
                }
                fallthrough = new Flow(groupFields, reachable);
            }
            breakFlows.pop();
            exitScope();
            boolean hasDefault = switched.groups().stream()
                    .flatMap(group -> group.labels().stream())
                    .anyMatch(label -> label.isDefault());
            if (!hasDefault) {
                normal.add(copy(fields));
            }
            if (fallthrough != null && fallthrough.reachable()) {
                normal.add(fallthrough.fields());
            }
            normal.addAll(switchBreaks);
            if (normal.isEmpty()) {
                return new Flow(fields, false, returns, throwsFlows);
            }
            Map<FieldSymbol, State> result = copy(normal.getFirst());
            for (int index = 1; index < normal.size(); index++) {
                result = join(result, normal.get(index));
            }
            return new Flow(result, true, returns, throwsFlows);
        }
        if (statement instanceof ModernSwitchStatement switched) {
            scanExpression(switched.selector(), fields, inLoop);
            List<Map<FieldSymbol, State>> normal = new ArrayList<>();
            List<Map<FieldSymbol, State>> returns = new ArrayList<>();
            List<Map<FieldSymbol, State>> throwsFlows = new ArrayList<>();
            List<Map<FieldSymbol, State>> switchBreaks = new ArrayList<>();
            breakFlows.push(switchBreaks);
            enterScope();
            for (var rule : switched.rules()) {
                Flow flow = scanSwitchRuleBody(rule.body(), copy(fields), inLoop);
                if (flow.reachable()) {
                    normal.add(flow.fields());
                }
                returns.addAll(flow.returns());
                throwsFlows.addAll(flow.throwsFlows());
            }
            exitScope();
            breakFlows.pop();
            normal.addAll(switchBreaks);
            boolean hasDefault = switched.rules().stream()
                    .flatMap(rule -> rule.labels().stream())
                    .anyMatch(label -> label.isDefault());
            if (!hasDefault) {
                normal.add(copy(fields));
            }
            if (normal.isEmpty()) {
                return new Flow(fields, false, returns, throwsFlows);
            }
            Map<FieldSymbol, State> result = copy(normal.getFirst());
            for (int index = 1; index < normal.size(); index++) {
                result = join(result, normal.get(index));
            }
            return new Flow(result, true, returns, throwsFlows);
        }
        if (statement instanceof TryStatement guarded) {
            Map<FieldSymbol, State> before = copy(fields);
            List<TransferSnapshot> transferSnapshots = guarded.finallyBlock().isPresent()
                    ? snapshotTransferFlows() : List.of();
            Flow tried = scanBlock(guarded.body(), fields, true, inLoop);
            List<Flow> alternatives = new ArrayList<>();
            alternatives.add(tried);
            Map<FieldSymbol, State> possibleExceptionState = possibleState(before, tried);
            for (var caught : guarded.catches()) {
                enterScope();
                declare(caught.variableName());
                Flow catchFlow = scanBlock(caught.body(), possibleExceptionState, false, inLoop);
                exitScope();
                alternatives.add(catchFlow);
            }
            Flow merged = mergeFlows(alternatives);
            if (guarded.finallyBlock().isPresent()) {
                List<PendingTransfer> pendingTransfers = detachTransferFlows(transferSnapshots);
                List<Map<FieldSymbol, State>> exceptional = new ArrayList<>(merged.throwsFlows());
                // Any expression in the try may fail before its following assignment completes.
                // Preserve that conservative exceptional path so finally cannot assume the
                // try's normally completed field state.
                exceptional.add(possibleExceptionState);
                Flow withImplicitException = new Flow(merged.fields(), merged.reachable(),
                        merged.returns(), exceptional);
                Block cleanup = guarded.finallyBlock().orElseThrow();
                Flow cleaned = applyFinally(withImplicitException, cleanup, inLoop);
                return applyFinallyToTransfers(cleaned, pendingTransfers, cleanup, inLoop);
            }
            return merged;
        }
        if (statement instanceof ironwood.compiler.ast.BreakStatement transfer) {
            if (transfer.label().isPresent()) {
                labeledBreakFlows.stream()
                        .filter(context -> context.label().equals(
                                transfer.label().orElseThrow()))
                        .findFirst()
                        .ifPresent(context -> context.flows().add(copy(fields)));
            } else if (!breakFlows.isEmpty()) {
                breakFlows.peek().add(copy(fields));
            }
            return new Flow(fields, false);
        }
        if (statement instanceof ironwood.compiler.ast.ContinueStatement) {
            return new Flow(fields, false);
        }
        // Constructor invocations have already been separated from the body by the parser.
        return new Flow(fields, true);
    }

    private Flow scanScoped(Statement statement, Map<FieldSymbol, State> fields, boolean inLoop) {
        enterScope();
        Flow result = scanStatement(statement, copy(fields), inLoop);
        exitScope();
        return result;
    }

    private void scanExpression(Expression expression, Map<FieldSymbol, State> fields,
                                boolean inLoop) {
        if (expression instanceof SwitchExpression switched) {
            scanExpression(switched.selector(), fields, inLoop);
            List<Map<FieldSymbol, State>> yielded = new ArrayList<>();
            yieldFlows.push(yielded);
            enterScope();
            if (switched.arrowRules()) {
                for (var rule : switched.rules()) {
                    if (rule.body() instanceof SwitchRuleExpression result) {
                        Map<FieldSymbol, State> branch = copy(fields);
                        scanExpression(result.expression(), branch, inLoop);
                        yielded.add(branch);
                    } else {
                        Flow flow = scanSwitchRuleBody(rule.body(), copy(fields), inLoop);
                        if (flow.reachable()) {
                            yielded.add(flow.fields());
                        }
                    }
                }
            } else {
                Flow fallthrough = null;
                for (var group : switched.groups()) {
                    Map<FieldSymbol, State> branch = fallthrough != null && fallthrough.reachable()
                            ? join(fields, fallthrough.fields()) : copy(fields);
                    boolean reachable = true;
                    for (Statement child : group.statements()) {
                        if (!reachable) {
                            break;
                        }
                        Flow flow = scanStatement(child, branch, inLoop);
                        branch = flow.fields();
                        reachable = flow.reachable();
                    }
                    fallthrough = new Flow(branch, reachable);
                }
                if (fallthrough != null && fallthrough.reachable()) {
                    yielded.add(fallthrough.fields());
                }
            }
            exitScope();
            yieldFlows.pop();
            if (yielded.isEmpty()) {
                yielded.add(copy(fields));
            }
            Map<FieldSymbol, State> result = copy(yielded.getFirst());
            for (int index = 1; index < yielded.size(); index++) {
                result = join(result, yielded.get(index));
            }
            fields.clear();
            fields.putAll(result);
        } else if (expression instanceof AssignmentExpression assignment) {
            scanExpression(assignment.value(), fields, inLoop);
            assign(assignment.target(), assignment.operator(), assignment.operatorSpan(), fields, inLoop);
        } else if (expression instanceof UpdateExpression update) {
            assign(update.target(), AssignmentOperator.ADD, update.operatorSpan(), fields, inLoop);
        } else if (expression instanceof NameExpression name) {
            FieldSymbol field = unqualifiedFinalField(name.name());
            if (field != null) {
                checkRead(field, name.span(), fields);
            }
        } else if (expression instanceof FieldAccessExpression access) {
            scanExpression(access.receiver(), fields, inLoop);
            if (access.receiver() instanceof ThisExpression) {
                FieldSymbol field = declaredFinalField(access.fieldName());
                if (field != null) {
                    checkRead(field, access.fieldNameSpan(), fields);
                }
            }
        } else if (expression instanceof BinaryExpression binary) {
            scanExpression(binary.left(), fields, inLoop);
            if (binary.operator() == ironwood.compiler.ast.BinaryOperator.LOGICAL_AND
                    || binary.operator() == ironwood.compiler.ast.BinaryOperator.LOGICAL_OR) {
                Map<FieldSymbol, State> withoutRight = copy(fields);
                Map<FieldSymbol, State> withRight = copy(fields);
                scanExpression(binary.right(), withRight, inLoop);
                fields.clear();
                fields.putAll(join(withoutRight, withRight));
            } else {
                scanExpression(binary.right(), fields, inLoop);
            }
        } else if (expression instanceof ConditionalExpression conditional) {
            scanExpression(conditional.condition(), fields, inLoop);
            Map<FieldSymbol, State> whenTrue = copy(fields);
            Map<FieldSymbol, State> whenFalse = copy(fields);
            scanExpression(conditional.whenTrue(), whenTrue, inLoop);
            scanExpression(conditional.whenFalse(), whenFalse, inLoop);
            fields.clear();
            fields.putAll(join(whenTrue, whenFalse));
        } else if (expression instanceof UnaryExpression unary) {
            scanExpression(unary.operand(), fields, inLoop);
        } else if (expression instanceof CastExpression cast) {
            scanExpression(cast.operand(), fields, inLoop);
        } else if (expression instanceof CallExpression call) {
            call.receiver().ifPresent(receiver -> scanExpression(receiver, fields, inLoop));
            call.arguments().forEach(argument -> scanExpression(argument, fields, inLoop));
        } else if (expression instanceof NewExpression creation) {
            creation.enclosingInstance().ifPresent(enclosing -> scanExpression(enclosing, fields, inLoop));
            creation.arguments().forEach(argument -> scanExpression(argument, fields, inLoop));
        } else if (expression instanceof QualifiedSuperConstructorExpression invocation) {
            scanExpression(invocation.enclosingInstance(), fields, inLoop);
            invocation.arguments().forEach(argument -> scanExpression(argument, fields, inLoop));
        } else if (expression instanceof QualifiedThisExpression) {
            // Enclosing receivers do not denote a blank final of the current class.
        } else if (expression instanceof ArrayCreationExpression creation) {
            creation.length().ifPresent(length -> scanExpression(length, fields, inLoop));
            creation.initializer().ifPresent(initializer ->
                    scanExpression(initializer, fields, inLoop));
        } else if (expression instanceof ArrayInitializerExpression initializer) {
            initializer.elements().forEach(element -> scanExpression(element, fields, inLoop));
        } else if (expression instanceof ArrayAccessExpression access) {
            scanExpression(access.array(), fields, inLoop);
            scanExpression(access.index(), fields, inLoop);
        } else if (expression instanceof InstanceOfExpression typeTest) {
            scanExpression(typeTest.operand(), fields, inLoop);
        }
    }

    private Flow scanSwitchRuleBody(ironwood.compiler.ast.SwitchRuleBody body,
                                    Map<FieldSymbol, State> fields, boolean inLoop) {
        if (body instanceof SwitchRuleExpression result) {
            scanExpression(result.expression(), fields, inLoop);
            return new Flow(fields, true);
        }
        if (body instanceof SwitchRuleBlock block) {
            return scanBlock(block.block(), fields, true, inLoop);
        }
        if (body instanceof SwitchRuleThrow thrown) {
            return scanStatement(thrown.statement(), fields, inLoop);
        }
        throw new IllegalStateException("unsupported switch rule body");
    }

    private void assign(Expression target, AssignmentOperator operator, SourceSpan span,
                        Map<FieldSymbol, State> fields, boolean inLoop) {
        FieldSymbol field = assignmentField(target);
        if (field == null) {
            scanExpression(target, fields, inLoop);
            return;
        }
        State state = fields.get(field);
        if (operator != AssignmentOperator.ASSIGN) {
            diagnostics.add(error(span, "blank final field '" + field.declaration().name()
                    + "' cannot be updated with a compound assignment or increment"));
            return;
        }
        if (inLoop) {
            diagnostics.add(error(span, "blank final field '" + field.declaration().name()
                    + "' cannot be assigned in a loop that may execute more than once"));
            fields.put(field, State.MAYBE_ASSIGNED);
            return;
        }
        if (state != State.UNASSIGNED) {
            diagnostics.add(error(span, "final field '" + field.declaration().name()
                    + "' may only be assigned once"));
        }
        fields.put(field, State.ASSIGNED);
    }

    private FieldSymbol assignmentField(Expression target) {
        if (target instanceof NameExpression name) {
            return unqualifiedFinalField(name.name());
        }
        if (!staticMode && target instanceof FieldAccessExpression access
                && access.receiver() instanceof ThisExpression) {
            return declaredFinalField(access.fieldName());
        }
        return null;
    }

    private FieldSymbol unqualifiedFinalField(String name) {
        if (isLocal(name)) {
            return null;
        }
        return declaredFinalField(name);
    }

    private FieldSymbol declaredFinalField(String name) {
        FieldSymbol field = type.declaredFields().get(name);
        return field != null && field.isStatic() == staticMode && field.isFinal() ? field : null;
    }

    private void checkRead(FieldSymbol field, SourceSpan span, Map<FieldSymbol, State> fields) {
        if (fields.get(field) != State.ASSIGNED) {
            diagnostics.add(error(span, "blank final field '" + field.declaration().name()
                    + "' might not have been initialized"));
        }
    }

    private void requireAssigned(Map<FieldSymbol, State> fields, SourceSpan span) {
        for (FieldSymbol field : finalFields) {
            if (fields.get(field) != State.ASSIGNED) {
                diagnostics.add(error(span, "blank " + (staticMode ? "static " : "")
                        + "final field '" + field.declaration().name()
                        + "' is not definitely assigned by "
                        + (staticMode ? "static initialization" : "this constructor")));
            }
        }
    }

    private Flow mergeFlows(List<Flow> flows) {
        List<Flow> reachable = flows.stream().filter(Flow::reachable).toList();
        List<Map<FieldSymbol, State>> returns = flows.stream()
                .flatMap(flow -> flow.returns().stream()).map(FinalFieldAssignmentAnalyzer::copy).toList();
        List<Map<FieldSymbol, State>> throwsFlows = flows.stream()
                .flatMap(flow -> flow.throwsFlows().stream()).map(FinalFieldAssignmentAnalyzer::copy).toList();
        if (reachable.isEmpty()) {
            return new Flow(copy(flows.getFirst().fields()), false, returns, throwsFlows);
        }
        Map<FieldSymbol, State> result = copy(reachable.getFirst().fields());
        for (int index = 1; index < reachable.size(); index++) {
            result = join(result, reachable.get(index).fields());
        }
        return new Flow(result, true, returns, throwsFlows);
    }

    private Map<FieldSymbol, State> possibleState(Map<FieldSymbol, State> before, Flow flow) {
        Map<FieldSymbol, State> result = copy(before);
        result = join(result, flow.fields());
        for (Map<FieldSymbol, State> returned : flow.returns()) {
            result = join(result, returned);
        }
        for (Map<FieldSymbol, State> thrown : flow.throwsFlows()) {
            result = join(result, thrown);
        }
        return result;
    }

    private Flow applyFinally(Flow incoming, Block cleanup, boolean inLoop) {
        List<Map<FieldSymbol, State>> normal = new ArrayList<>();
        List<Map<FieldSymbol, State>> returns = new ArrayList<>();
        List<Map<FieldSymbol, State>> throwsFlows = new ArrayList<>();
        if (incoming.reachable()) {
            Flow cleaned = scanBlock(cleanup, incoming.fields(), true, inLoop);
            if (cleaned.reachable()) {
                normal.add(cleaned.fields());
            }
            returns.addAll(cleaned.returns());
            throwsFlows.addAll(cleaned.throwsFlows());
        }
        for (Map<FieldSymbol, State> returned : incoming.returns()) {
            Flow cleaned = scanBlock(cleanup, returned, true, inLoop);
            if (cleaned.reachable()) {
                returns.add(cleaned.fields());
            }
            returns.addAll(cleaned.returns());
            throwsFlows.addAll(cleaned.throwsFlows());
        }
        for (Map<FieldSymbol, State> thrown : incoming.throwsFlows()) {
            Flow cleaned = scanBlock(cleanup, thrown, true, inLoop);
            if (cleaned.reachable()) {
                throwsFlows.add(cleaned.fields());
            }
            returns.addAll(cleaned.returns());
            throwsFlows.addAll(cleaned.throwsFlows());
        }
        if (normal.isEmpty()) {
            return new Flow(copy(incoming.fields()), false, returns, throwsFlows);
        }
        Map<FieldSymbol, State> normalState = copy(normal.getFirst());
        for (int index = 1; index < normal.size(); index++) {
            normalState = join(normalState, normal.get(index));
        }
        return new Flow(normalState, true, returns, throwsFlows);
    }

    private List<TransferSnapshot> snapshotTransferFlows() {
        List<TransferSnapshot> snapshots = new ArrayList<>();
        breakFlows.forEach(flows -> snapshots.add(new TransferSnapshot(flows, flows.size())));
        labeledBreakFlows.forEach(context -> snapshots.add(
                new TransferSnapshot(context.flows(), context.flows().size())));
        yieldFlows.forEach(flows -> snapshots.add(new TransferSnapshot(flows, flows.size())));
        return List.copyOf(snapshots);
    }

    private List<PendingTransfer> detachTransferFlows(List<TransferSnapshot> snapshots) {
        List<PendingTransfer> pending = new ArrayList<>();
        for (TransferSnapshot snapshot : snapshots) {
            List<Map<FieldSymbol, State>> flows = snapshot.flows();
            List<Map<FieldSymbol, State>> added = new ArrayList<>(
                    flows.subList(snapshot.size(), flows.size()));
            flows.subList(snapshot.size(), flows.size()).clear();
            if (!added.isEmpty()) {
                pending.add(new PendingTransfer(flows, added));
            }
        }
        return List.copyOf(pending);
    }

    private Flow applyFinallyToTransfers(Flow incoming,
                                         List<PendingTransfer> pendingTransfers,
                                         Block cleanup, boolean inLoop) {
        List<Map<FieldSymbol, State>> returns = new ArrayList<>(incoming.returns());
        List<Map<FieldSymbol, State>> throwsFlows = new ArrayList<>(incoming.throwsFlows());
        for (PendingTransfer pending : pendingTransfers) {
            for (Map<FieldSymbol, State> fields : pending.flows()) {
                Flow cleaned = scanBlock(cleanup, fields, true, inLoop);
                if (cleaned.reachable()) {
                    pending.target().add(cleaned.fields());
                }
                returns.addAll(cleaned.returns());
                throwsFlows.addAll(cleaned.throwsFlows());
            }
        }
        return new Flow(incoming.fields(), incoming.reachable(), returns, throwsFlows);
    }

    private Map<FieldSymbol, State> join(Map<FieldSymbol, State> left,
                                         Map<FieldSymbol, State> right) {
        Map<FieldSymbol, State> result = new LinkedHashMap<>();
        for (FieldSymbol field : finalFields) {
            State leftState = left.get(field);
            State rightState = right.get(field);
            result.put(field, leftState == rightState ? leftState : State.MAYBE_ASSIGNED);
        }
        return result;
    }

    private static Map<FieldSymbol, State> copy(Map<FieldSymbol, State> fields) {
        return new LinkedHashMap<>(fields);
    }

    private void enterScope() {
        scopes.push(new LinkedHashSet<>());
    }

    private void exitScope() {
        scopes.pop();
    }

    private void declare(String name) {
        scopes.peek().add(name);
    }

    private boolean isLocal(String name) {
        return scopes.stream().anyMatch(scope -> scope.contains(name));
    }

    private Diagnostic error(SourceSpan span, String message) {
        return Diagnostic.error(source, span, message);
    }

    private enum State {
        UNASSIGNED,
        ASSIGNED,
        MAYBE_ASSIGNED
    }

    private record LabeledBreakFlows(String label, List<Map<FieldSymbol, State>> flows) {
    }

    private record TransferSnapshot(List<Map<FieldSymbol, State>> flows, int size) {
    }

    private record PendingTransfer(List<Map<FieldSymbol, State>> target,
                                   List<Map<FieldSymbol, State>> flows) {
    }

    private record Flow(Map<FieldSymbol, State> fields, boolean reachable,
                        List<Map<FieldSymbol, State>> returns,
                        List<Map<FieldSymbol, State>> throwsFlows) {
        private Flow(Map<FieldSymbol, State> fields, boolean reachable) {
            this(fields, reachable, List.of(), List.of());
        }

        private Flow {
            fields = copy(fields);
            returns = List.copyOf(returns);
            throwsFlows = List.copyOf(throwsFlows);
        }
    }
}
