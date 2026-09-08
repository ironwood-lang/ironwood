// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.AnonymousClassBody;
import ironwood.compiler.ast.ArrayAccessExpression;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.AssignmentExpression;
import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.CatchClause;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
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
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.InterfaceSuperExpression;
import ironwood.compiler.ast.LocalClassDeclaration;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.NullLiteralExpression;
import ironwood.compiler.ast.PatternFlow;
import ironwood.compiler.ast.QualifiedThisExpression;
import ironwood.compiler.ast.QualifiedSuperConstructorExpression;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.SuperConstructorInvocation;
import ironwood.compiler.ast.SwitchExpression;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchRuleThrow;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.StringLiteralExpression;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.TypeDeclaration;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.TypeReference;
import ironwood.compiler.ast.TypeParameter;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UpdateExpression;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TypeDependencyScanner {
    Set<String> scan(CompilationUnit unit) {
        Set<String> names = new LinkedHashSet<>();
        unit.imports().stream()
                .filter(imported -> imported.staticImport() || !imported.wildcard())
                .map(imported -> imported.staticImport()
                        ? imported.ownerName() : imported.name())
                .filter(name -> !name.isEmpty()).forEach(names::add);
        for (TypeDeclaration declaration : unit.declarations()) {
            scanDeclaration(declaration, names, Set.of(), Set.of());
        }
        if (names.stream().anyMatch(TypeDependencyScanner::requiresImplicitAllocation)) {
            names.add("ironwood.lang.OutOfMemoryError");
        }
        return names;
    }

    private static boolean requiresImplicitAllocation(String name) {
        return switch (name) {
            case "ironwood.lang.ArithmeticException",
                    "ironwood.lang.ArrayIndexOutOfBoundsException",
                    "ironwood.lang.ClassCastException",
                    "ironwood.lang.NegativeArraySizeException",
                    "ironwood.lang.NullPointerException",
                    "ironwood.lang.StringIndexOutOfBoundsException" -> true;
            default -> false;
        };
    }

    private void scanDeclaration(TypeDeclaration declaration, Set<String> names,
                                 Set<String> enclosingTypeParameters,
                                 Set<String> enclosingValues) {
        Set<String> typeParameters = declaration.isStatic()
                ? new LinkedHashSet<>() : new LinkedHashSet<>(enclosingTypeParameters);
        Set<String> capturedValues = declaration.isStatic()
                ? new LinkedHashSet<>() : new LinkedHashSet<>(enclosingValues);
        declaration.typeParameters().stream().map(TypeParameter::name).forEach(typeParameters::add);
        declaration.typeParameters().forEach(parameter ->
                parameter.upperBounds().forEach(bound -> add(bound, names, typeParameters)));
        if (declaration instanceof ClassDeclaration classDeclaration) {
                if (classDeclaration.enumType()) {
                    names.add("ironwood.lang.Enum");
                    names.add("ironwood.lang.String");
                    names.add("ironwood.lang.IllegalArgumentException");
                    names.add("ironwood.lang.NullPointerException");
                }
                classDeclaration.superclass().ifPresent(reference -> add(reference, names, typeParameters));
                classDeclaration.implementedInterfaces().forEach(reference ->
                        add(reference, names, typeParameters));
                Set<String> fieldValues = new LinkedHashSet<>(capturedValues);
                classDeclaration.fields().forEach(field -> fieldValues.add(field.name()));
                classDeclaration.enumConstants().forEach(constant ->
                        fieldValues.add(constant.name()));
                classDeclaration.enumConstants().forEach(constant ->
                        constant.arguments().forEach(argument ->
                                scan(argument, names, fieldValues, typeParameters)));
                classDeclaration.enumConstants().forEach(constant ->
                        constant.classBody().ifPresent(body ->
                                scanAnonymousClassBody(body, names, fieldValues, typeParameters)));
                classDeclaration.fields().forEach(field -> {
                    add(field.type(), names, typeParameters);
                    field.initializer().ifPresent(initializer -> {
                        scan(initializer, names, fieldValues, typeParameters);
                    });
                });
                classDeclaration.staticInitializations().stream()
                        .filter(Block.class::isInstance).map(Block.class::cast)
                        .forEach(block -> scan(block, names,
                                new LinkedHashSet<>(fieldValues), typeParameters));
                classDeclaration.instanceInitializations().stream()
                        .filter(Block.class::isInstance).map(Block.class::cast)
                        .forEach(block -> scan(block, names,
                                new LinkedHashSet<>(fieldValues), typeParameters));
                classDeclaration.constructors().forEach(constructor -> {
                    Set<String> callableTypeParameters = callableTypeParameters(
                            constructor.typeParameters(), typeParameters, names);
                    constructor.parameters().forEach(parameter ->
                            add(parameter.type(), names, callableTypeParameters));
                    constructor.thrownTypes().forEach(type ->
                            addExceptionType(type, names, callableTypeParameters));
                    Set<String> values = new LinkedHashSet<>(fieldValues);
                    constructor.parameters().forEach(parameter -> values.add(parameter.name()));
                    constructor.superInvocation().ifPresent(invocation ->
                            scan(invocation, names, values, callableTypeParameters));
                    constructor.thisInvocation().ifPresent(invocation ->
                            scan(invocation, names, values, callableTypeParameters));
                    scan(constructor.body(), names, values, callableTypeParameters);
                });
                classDeclaration.methods().forEach(method -> {
                    if (method.hasTestDirective()) {
                        names.add("ironwood.testing.TestSuite");
                        names.add("ironwood.testing.TestRunner");
                        names.add("ironwood.lang.IllegalArgumentException");
                    }
                    if (method.name().equals("toString") || method.name().equals("fromChars")
                            || method.name().equals("fromRange")
                            || method.name().equals("fromInteger")
                            || method.name().equals("fromCharacter")
                            || method.name().equals("environmentValue")
                            || isFileIntrinsic(method.name())) {
                        names.add("ironwood.lang.OutOfMemoryError");
                    }
                    Set<String> callableTypeParameters = callableTypeParameters(
                            method.typeParameters(), typeParameters, names);
                    add(method.returnType(), names, callableTypeParameters);
                    method.parameters().forEach(parameter ->
                            add(parameter.type(), names, callableTypeParameters));
                    method.thrownTypes().forEach(type ->
                            addExceptionType(type, names, callableTypeParameters));
                    Set<String> values = new LinkedHashSet<>(fieldValues);
                    method.parameters().forEach(parameter -> values.add(parameter.name()));
                    method.body().ifPresent(body -> scan(body, names, values,
                            callableTypeParameters));
                });
        } else {
            InterfaceDeclaration interfaceDeclaration = (InterfaceDeclaration) declaration;
                interfaceDeclaration.extendedInterfaces().forEach(reference ->
                        add(reference, names, typeParameters));
                Set<String> fieldValues = new LinkedHashSet<>(capturedValues);
                interfaceDeclaration.fields().forEach(field -> fieldValues.add(field.name()));
                interfaceDeclaration.fields().forEach(field -> {
                    add(field.type(), names, typeParameters);
                    field.initializer().ifPresent(initializer ->
                            scan(initializer, names, fieldValues, typeParameters));
                });
                interfaceDeclaration.methods().forEach(method -> {
                    Set<String> callableTypeParameters = callableTypeParameters(
                            method.typeParameters(), typeParameters, names);
                    add(method.returnType(), names, callableTypeParameters);
                    method.parameters().forEach(parameter ->
                            add(parameter.type(), names, callableTypeParameters));
                    method.thrownTypes().forEach(type ->
                            addExceptionType(type, names, callableTypeParameters));
                    Set<String> values = new LinkedHashSet<>(fieldValues);
                    method.parameters().forEach(parameter -> values.add(parameter.name()));
                    method.body().ifPresent(body -> scan(body, names, values,
                            callableTypeParameters));
                });
        }
        Set<String> memberValues = new LinkedHashSet<>(capturedValues);
        if (declaration instanceof ClassDeclaration classDeclaration) {
            classDeclaration.fields().forEach(field -> memberValues.add(field.name()));
            classDeclaration.enumConstants().forEach(constant ->
                    memberValues.add(constant.name()));
        } else {
            ((InterfaceDeclaration) declaration).fields().forEach(field ->
                    memberValues.add(field.name()));
        }
        declaration.memberTypes().forEach(member ->
                scanDeclaration(member, names, typeParameters, memberValues));
    }

    private Set<String> callableTypeParameters(List<TypeParameter> declared,
                                               Set<String> enclosing,
                                               Set<String> names) {
        Set<String> parameters = new LinkedHashSet<>(enclosing);
        declared.stream().map(TypeParameter::name).forEach(parameters::add);
        declared.forEach(parameter -> parameter.upperBounds()
                .forEach(bound -> add(bound, names, parameters)));
        return parameters;
    }

    private void add(TypeName type, Set<String> names, Set<String> typeParameters) {
        if (type.kind() == TypeName.Kind.REFERENCE) {
            if (!isScopedTypeName(type.referenceName(), typeParameters)) {
                names.add(type.referenceName());
            }
            type.typeArguments().forEach(argument -> add(argument, names, typeParameters));
        } else if (type.kind() == TypeName.Kind.ARRAY) {
            add(type.elementType(), names, typeParameters);
        } else if (type.kind() == TypeName.Kind.WILDCARD && type.wildcardBound() != null) {
            add(type.wildcardBound(), names, typeParameters);
        }
    }

    private void addExceptionType(TypeName type, Set<String> names,
                                  Set<String> typeParameters) {
        add(type, names, typeParameters);
        names.add("ironwood.lang.Throwable");
        names.add("ironwood.lang.RuntimeException");
        names.add("ironwood.lang.Error");
    }

    private void add(TypeReference type, Set<String> names,
                     Set<String> typeParameters) {
        if (!isScopedTypeName(type.name(), typeParameters)) {
            names.add(type.name());
        }
        type.typeArguments().forEach(argument -> add(argument, names, typeParameters));
    }

    private static boolean isScopedTypeName(String name, Set<String> scopedNames) {
        return scopedNames.stream().anyMatch(scoped -> name.equals(scoped)
                || name.startsWith(scoped + "."));
    }

    private void scan(Statement statement, Set<String> names, Set<String> values,
                      Set<String> typeParameters) {
        if (statement instanceof Block block) {
            Set<String> blockValues = new LinkedHashSet<>(values);
            Set<String> blockTypeNames = new LinkedHashSet<>(typeParameters);
            for (Statement child : block.statements()) {
                scan(child, names, blockValues, blockTypeNames);
                if (child instanceof LocalClassDeclaration localClass) {
                    blockTypeNames.add(localClass.declaration().name());
                }
            }
        } else if (statement instanceof LocalClassDeclaration localClass) {
            Set<String> localTypeNames = new LinkedHashSet<>(typeParameters);
            localTypeNames.add(localClass.declaration().name());
            scanDeclaration(localClass.declaration(), names, localTypeNames, values);
        } else if (statement instanceof LocalVariableDeclaration declaration) {
            add(declaration.type(), names, typeParameters);
            scan(declaration.initializer(), names, values, typeParameters);
            values.add(declaration.name());
        } else if (statement instanceof AssignmentStatement assignment) {
            scan(assignment.target(), names, values, typeParameters);
            scan(assignment.value(), names, values, typeParameters);
        } else if (statement instanceof ExpressionStatement expression) {
            scan(expression.expression(), names, values, typeParameters);
        } else if (statement instanceof FreeStatement free) {
            scan(free.value(), names, values, typeParameters);
        } else if (statement instanceof ReturnStatement returnStatement) {
            returnStatement.value().ifPresent(value -> scan(value, names, values, typeParameters));
        } else if (statement instanceof ThrowStatement throwStatement) {
            names.add("ironwood.lang.Throwable");
            names.add("ironwood.lang.RuntimeException");
            names.add("ironwood.lang.Error");
            names.add("ironwood.lang.NullPointerException");
            scan(throwStatement.value(), names, values, typeParameters);
        } else if (statement instanceof TryStatement tryStatement) {
            scan(tryStatement.body(), names, new LinkedHashSet<>(values), typeParameters);
            for (CatchClause catchClause : tryStatement.catches()) {
                names.add("ironwood.lang.Throwable");
                names.add("ironwood.lang.RuntimeException");
                names.add("ironwood.lang.Error");
                catchClause.types().forEach(type -> add(type, names, typeParameters));
                Set<String> catchValues = new LinkedHashSet<>(values);
                catchValues.add(catchClause.variableName());
                scan(catchClause.body(), names, catchValues, typeParameters);
            }
            tryStatement.finallyBlock().ifPresent(block ->
                    scan(block, names, new LinkedHashSet<>(values), typeParameters));
        } else if (statement instanceof IfStatement ifStatement) {
            scan(ifStatement.condition(), names, values, typeParameters);
            PatternFlow.Result flow = PatternFlow.analyze(ifStatement.condition());
            Set<String> thenValues = withPatternValues(values, flow.whenTrue());
            Set<String> elseValues = withPatternValues(values, flow.whenFalse());
            scan(ifStatement.thenBranch(), names, thenValues, typeParameters);
            ifStatement.elseBranch().ifPresent(branch -> scan(branch, names,
                    elseValues, typeParameters));
            boolean thenCompletes = PatternFlow.canCompleteNormally(ifStatement.thenBranch());
            boolean elseCompletes = ifStatement.elseBranch()
                    .map(PatternFlow::canCompleteNormally).orElse(true);
            if (!thenCompletes && elseCompletes) {
                addPatternValues(values, flow.whenFalse());
            } else if (thenCompletes && !elseCompletes) {
                addPatternValues(values, flow.whenTrue());
            }
        } else if (statement instanceof WhileStatement whileStatement) {
            scan(whileStatement.condition(), names, values, typeParameters);
            PatternFlow.Result flow = PatternFlow.analyze(whileStatement.condition());
            scan(whileStatement.body(), names,
                    withPatternValues(values, flow.whenTrue()), typeParameters);
            if (!PatternFlow.containsBreakForCurrentLoop(whileStatement.body())) {
                addPatternValues(values, flow.whenFalse());
            }
        } else if (statement instanceof DoWhileStatement doWhileStatement) {
            scan(doWhileStatement.body(), names, new LinkedHashSet<>(values), typeParameters);
            scan(doWhileStatement.condition(), names, values, typeParameters);
            PatternFlow.Result flow = PatternFlow.analyze(doWhileStatement.condition());
            if (!PatternFlow.containsBreakForCurrentLoop(doWhileStatement.body())) {
                addPatternValues(values, flow.whenFalse());
            }
        } else if (statement instanceof ForStatement forStatement) {
            Set<String> loopValues = new LinkedHashSet<>(values);
            forStatement.initializer().ifPresent(initializer ->
                    scan(initializer, names, loopValues, typeParameters));
            forStatement.condition().ifPresent(condition ->
                    scan(condition, names, loopValues, typeParameters));
            PatternFlow.Result flow = forStatement.condition()
                    .map(PatternFlow::analyze)
                    .orElseGet(() -> PatternFlow.analyze(
                            new ironwood.compiler.ast.BooleanLiteralExpression(
                                    true, forStatement.span())));
            Set<String> matchedValues = withPatternValues(loopValues, flow.whenTrue());
            forStatement.updates().forEach(update ->
                    scan(update, names, matchedValues, typeParameters));
            scan(forStatement.body(), names, new LinkedHashSet<>(matchedValues), typeParameters);
            if (!PatternFlow.containsBreakForCurrentLoop(forStatement.body())) {
                addPatternValues(values, flow.whenFalse());
            }
        } else if (statement instanceof EnhancedForStatement enhancedFor) {
            add(enhancedFor.variableType(), names, typeParameters);
            scan(enhancedFor.iterable(), names, values, typeParameters);
            names.add("ironwood.lang.NullPointerException");
            names.add("ironwood.lang.ArrayIndexOutOfBoundsException");
            Set<String> bodyValues = new LinkedHashSet<>(values);
            bodyValues.add(enhancedFor.variableName());
            scan(enhancedFor.body(), names, bodyValues, typeParameters);
        } else if (statement instanceof LabeledStatement labeled) {
            scan(labeled.body(), names, values, typeParameters);
        } else if (statement instanceof EmptyStatement) {
            // No dependencies.
        } else if (statement instanceof SwitchStatement switchStatement) {
            scan(switchStatement.selector(), names, values, typeParameters);
            switchStatement.groups().forEach(group -> group.labels().forEach(label ->
                    label.value().ifPresent(value ->
                            scan(value, names, values, typeParameters))));
            if (switchStatement.groups().stream().flatMap(group -> group.labels().stream())
                    .flatMap(label -> label.value().stream())
                    .noneMatch(NullLiteralExpression.class::isInstance)) {
                names.add("ironwood.lang.NullPointerException");
            }
            Set<String> switchValues = new LinkedHashSet<>(values);
            Set<String> switchTypeNames = new LinkedHashSet<>(typeParameters);
            for (var group : switchStatement.groups()) {
                for (Statement child : group.statements()) {
                    scan(child, names, switchValues, switchTypeNames);
                    if (child instanceof LocalClassDeclaration localClass) {
                        switchTypeNames.add(localClass.declaration().name());
                    }
                }
            }
        } else if (statement instanceof ModernSwitchStatement switchStatement) {
            scan(switchStatement.selector(), names, values, typeParameters);
            scanSwitchRules(switchStatement.rules(), names, values, typeParameters);
            if (switchStatement.rules().stream().flatMap(rule -> rule.labels().stream())
                    .flatMap(label -> label.value().stream())
                    .noneMatch(NullLiteralExpression.class::isInstance)) {
                names.add("ironwood.lang.NullPointerException");
            }
        } else if (statement instanceof YieldStatement yielded) {
            scan(yielded.value(), names, values, typeParameters);
        } else if (statement instanceof SuperConstructorInvocation invocation) {
            if (invocation.enclosingInstance().isPresent()) {
                names.add("ironwood.lang.NullPointerException");
            }
            invocation.typeArguments().forEach(argument -> add(argument, names, typeParameters));
            invocation.enclosingInstance().ifPresent(enclosing ->
                    scan(enclosing, names, values, typeParameters));
            invocation.arguments().forEach(argument -> scan(argument, names, values, typeParameters));
        } else if (statement instanceof ThisConstructorInvocation invocation) {
            invocation.typeArguments().forEach(argument -> add(argument, names, typeParameters));
            invocation.arguments().forEach(argument -> scan(argument, names, values, typeParameters));
        }
    }

    private void scan(Expression expression, Set<String> names, Set<String> values,
                      Set<String> typeParameters) {
        if (expression instanceof SwitchExpression switched) {
            scan(switched.selector(), names, values, typeParameters);
            if (switched.arrowRules()) {
                scanSwitchRules(switched.rules(), names, values, typeParameters);
            } else {
                switched.groups().forEach(group -> {
                    group.labels().forEach(label -> label.value().ifPresent(value ->
                            scan(value, names, values, typeParameters)));
                    group.statements().forEach(statement -> scan(statement, names,
                            new LinkedHashSet<>(values), typeParameters));
                });
            }
            boolean handlesNull = (switched.arrowRules()
                    ? switched.rules().stream().flatMap(rule -> rule.labels().stream())
                    : switched.groups().stream().flatMap(group -> group.labels().stream()))
                    .flatMap(label -> label.value().stream())
                    .anyMatch(NullLiteralExpression.class::isInstance);
            if (!handlesNull) {
                names.add("ironwood.lang.NullPointerException");
            }
        } else if (expression instanceof BinaryExpression binary) {
            if (binary.operator() == ironwood.compiler.ast.BinaryOperator.ADD) {
                names.add("ironwood.lang.OutOfMemoryError");
            }
            scan(binary.left(), names, values, typeParameters);
            Set<String> rightValues = values;
            if (binary.operator() == ironwood.compiler.ast.BinaryOperator.LOGICAL_AND
                    || binary.operator() == ironwood.compiler.ast.BinaryOperator.LOGICAL_OR) {
                rightValues = withPatternValues(values,
                        PatternFlow.bindingsForRightOperand(binary));
            }
            scan(binary.right(), names, rightValues, typeParameters);
            if (binary.operator() == ironwood.compiler.ast.BinaryOperator.DIVIDE
                    || binary.operator() == ironwood.compiler.ast.BinaryOperator.REMAINDER) {
                names.add("ironwood.lang.ArithmeticException");
            }
        } else if (expression instanceof AssignmentExpression assignment) {
            if (assignment.operator() == ironwood.compiler.ast.AssignmentOperator.ADD) {
                names.add("ironwood.lang.OutOfMemoryError");
            }
            scan(assignment.target(), names, values, typeParameters);
            scan(assignment.value(), names, values, typeParameters);
            if (assignment.operator() == ironwood.compiler.ast.AssignmentOperator.DIVIDE
                    || assignment.operator() == ironwood.compiler.ast.AssignmentOperator.REMAINDER) {
                names.add("ironwood.lang.ArithmeticException");
            }
        } else if (expression instanceof ConditionalExpression conditional) {
            scan(conditional.condition(), names, values, typeParameters);
            PatternFlow.Result flow = PatternFlow.analyze(conditional.condition());
            scan(conditional.whenTrue(), names,
                    withPatternValues(values, flow.whenTrue()), typeParameters);
            scan(conditional.whenFalse(), names,
                    withPatternValues(values, flow.whenFalse()), typeParameters);
        } else if (expression instanceof CastExpression cast) {
            add(cast.targetType(), names, typeParameters);
            scan(cast.operand(), names, values, typeParameters);
            if (cast.targetType().kind() == TypeName.Kind.REFERENCE
                    || cast.targetType().kind() == TypeName.Kind.ARRAY) {
                names.add("ironwood.lang.ClassCastException");
            }
        } else if (expression instanceof UpdateExpression update) {
            scan(update.target(), names, values, typeParameters);
        } else if (expression instanceof UnaryExpression unary) {
            scan(unary.operand(), names, values, typeParameters);
        } else if (expression instanceof FieldAccessExpression access) {
            String possibleType = qualifiedName(access.receiver());
            String root = rootName(access.receiver());
            boolean classQualifier = possibleType != null
                    && (root == null || !values.contains(root));
            if (classQualifier) {
                names.add(possibleType);
            }
            if (!classQualifier
                    || !(access.receiver() instanceof ironwood.compiler.ast.NameExpression)) {
                names.add("ironwood.lang.NullPointerException");
            }
            scan(access.receiver(), names, values, typeParameters);
        } else if (expression instanceof ArrayAccessExpression access) {
            names.add("ironwood.lang.NullPointerException");
            names.add("ironwood.lang.ArrayIndexOutOfBoundsException");
            scan(access.array(), names, values, typeParameters);
            scan(access.index(), names, values, typeParameters);
        } else if (expression instanceof ArrayCreationExpression creation) {
            names.add("ironwood.lang.OutOfMemoryError");
            add(creation.elementType(), names, typeParameters);
            creation.length().ifPresent(length -> {
                names.add("ironwood.lang.NegativeArraySizeException");
                scan(length, names, values, typeParameters);
            });
            creation.initializer().ifPresent(initializer ->
                    scan(initializer, names, values, typeParameters));
        } else if (expression instanceof ArrayInitializerExpression initializer) {
            names.add("ironwood.lang.OutOfMemoryError");
            initializer.elements().forEach(element ->
                    scan(element, names, values, typeParameters));
        } else if (expression instanceof CallExpression call) {
            if (call.methodName().equals("toString") || call.methodName().equals("fromChars")
                    || call.methodName().equals("fromRange")
                    || call.methodName().equals("fromInteger")
                    || call.methodName().equals("fromCharacter")
                    || call.methodName().equals("environmentValue")
                    || call.methodName().equals("propertyValue")
                    || call.methodName().equals("getStackTrace")
                    || isFileIntrinsic(call.methodName())) {
                names.add("ironwood.lang.OutOfMemoryError");
            }
            if (call.methodName().equals("charAt")) {
                names.add("ironwood.lang.StringIndexOutOfBoundsException");
            }
            call.receiver().ifPresent(receiver -> {
                String possibleType = qualifiedName(receiver);
                String root = rootName(receiver);
                boolean classQualifier = possibleType != null
                        && (root == null || !values.contains(root));
                if (classQualifier) {
                    names.add(possibleType);
                }
                if (!classQualifier
                        || !(receiver instanceof ironwood.compiler.ast.NameExpression)) {
                    names.add("ironwood.lang.NullPointerException");
                }
                scan(receiver, names, values, typeParameters);
            });
            call.typeArguments().forEach(argument -> add(argument, names, typeParameters));
            call.arguments().forEach(argument -> scan(argument, names, values, typeParameters));
        } else if (expression instanceof NewExpression creation) {
            names.add("ironwood.lang.OutOfMemoryError");
            if (creation.enclosingInstance().isPresent()) {
                names.add("ironwood.lang.NullPointerException");
            }
            add(creation.classType(), names, typeParameters);
            creation.constructorTypeArguments().forEach(argument ->
                    add(argument, names, typeParameters));
            creation.enclosingInstance().ifPresent(enclosing ->
                    scan(enclosing, names, values, typeParameters));
            creation.arguments().forEach(argument -> scan(argument, names, values, typeParameters));
            creation.anonymousClassBody().ifPresent(body ->
                    scanAnonymousClassBody(body, names, values, typeParameters));
        } else if (expression instanceof QualifiedThisExpression qualifiedThis) {
            names.add(qualifiedThis.typeName());
        } else if (expression instanceof QualifiedSuperConstructorExpression invocation) {
            names.add("ironwood.lang.NullPointerException");
            invocation.typeArguments().forEach(argument -> add(argument, names, typeParameters));
            scan(invocation.enclosingInstance(), names, values, typeParameters);
            invocation.arguments().forEach(argument -> scan(argument, names, values, typeParameters));
        } else if (expression instanceof InstanceOfExpression typeTest) {
            scan(typeTest.operand(), names, values, typeParameters);
            add(typeTest.targetType(), names, typeParameters);
        } else if (expression instanceof InterfaceSuperExpression interfaceSuper) {
            names.add(interfaceSuper.interfaceName());
        } else if (expression instanceof StringLiteralExpression) {
            names.add("ironwood.lang.String");
        }
    }

    private static boolean isFileIntrinsic(String name) {
        return switch (name) {
            case "readAllBytesValue", "readStringValue", "writeBytesValue",
                    "writeStringValue", "writeCharsValue", "deleteValue", "createDirectoriesValue",
                    "copyValue", "moveValue", "openDirectoryValue", "directoryHasNextValue",
                    "nextDirectoryEntryValue", "closeDirectoryValue", "readAttributesValue",
                    "fileKind", "fileKindNoFollow", "fileSize",
                    "currentDirectoryValue",
                    "absolutePathValue", "resolveSibling",
                    "normalizeSyntax", "normalizePath", "fileName", "parent", "resolve" -> true;
            default -> false;
        };
    }

    private void scanSwitchRules(List<ironwood.compiler.ast.SwitchRule> rules,
                                 Set<String> names, Set<String> values,
                                 Set<String> typeParameters) {
        rules.forEach(rule -> {
            rule.labels().forEach(label -> label.value().ifPresent(value ->
                    scan(value, names, values, typeParameters)));
            if (rule.body() instanceof SwitchRuleExpression expression) {
                scan(expression.expression(), names, values, typeParameters);
            } else if (rule.body() instanceof SwitchRuleBlock block) {
                scan(block.block(), names, new LinkedHashSet<>(values), typeParameters);
            } else if (rule.body() instanceof SwitchRuleThrow thrown) {
                scan(thrown.statement(), names, values, typeParameters);
            }
        });
    }

    private static Set<String> withPatternValues(Set<String> values,
                                                  List<PatternFlow.Binding> bindings) {
        Set<String> result = new LinkedHashSet<>(values);
        addPatternValues(result, bindings);
        return result;
    }

    private static void addPatternValues(Set<String> values,
                                         List<PatternFlow.Binding> bindings) {
        bindings.stream().map(binding -> binding.variable().name()).forEach(values::add);
    }

    private void scanAnonymousClassBody(AnonymousClassBody body, Set<String> names,
                                        Set<String> enclosingValues,
                                        Set<String> typeParameters) {
        Set<String> fieldValues = new LinkedHashSet<>(enclosingValues);
        body.fields().forEach(field -> fieldValues.add(field.name()));
        body.fields().forEach(field -> {
            add(field.type(), names, typeParameters);
            field.initializer().ifPresent(initializer ->
                    scan(initializer, names, fieldValues, typeParameters));
        });
        body.instanceInitializations().stream()
                .filter(Block.class::isInstance).map(Block.class::cast)
                .forEach(block -> scan(block, names,
                        new LinkedHashSet<>(fieldValues), typeParameters));
        body.methods().forEach(method -> {
            Set<String> callableTypeParameters = callableTypeParameters(
                    method.typeParameters(), typeParameters, names);
            add(method.returnType(), names, callableTypeParameters);
            method.parameters().forEach(parameter ->
                    add(parameter.type(), names, callableTypeParameters));
            Set<String> values = new LinkedHashSet<>(fieldValues);
            method.parameters().forEach(parameter -> values.add(parameter.name()));
            method.body().ifPresent(block ->
                    scan(block, names, values, callableTypeParameters));
        });
        body.memberTypes().forEach(member ->
                scanDeclaration(member, names, typeParameters, fieldValues));
    }

    private String qualifiedName(Expression expression) {
        if (expression instanceof ironwood.compiler.ast.NameExpression name) {
            return name.name();
        }
        if (expression instanceof FieldAccessExpression access) {
            String receiver = qualifiedName(access.receiver());
            return receiver == null ? null : receiver + "." + access.fieldName();
        }
        return null;
    }

    private static String rootName(Expression expression) {
        if (expression instanceof ironwood.compiler.ast.NameExpression name) {
            return name.name();
        }
        if (expression instanceof FieldAccessExpression access) {
            return rootName(access.receiver());
        }
        return null;
    }
}
