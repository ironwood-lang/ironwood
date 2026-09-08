// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.semantic.InvocationCandidate.CallableTypeParameter;
import ironwood.compiler.semantic.InvocationPlan.ApplicabilityPhase;
import ironwood.compiler.semantic.InvocationPlan.ArgumentPlan;
import ironwood.compiler.semantic.InvocationPlan.CandidatePlan;
import ironwood.compiler.semantic.InvocationPlan.CandidateRejection;
import ironwood.compiler.semantic.InvocationPlan.InferenceSolution;
import ironwood.compiler.semantic.InvocationPlan.InvocationConversion;
import ironwood.compiler.semantic.InvocationPlan.InvocationKind;
import ironwood.compiler.semantic.InvocationPlan.ReceiverKind;
import ironwood.compiler.semantic.InvocationPlan.ReceiverPlan;
import ironwood.compiler.semantic.InvocationPlanningContext.AccessDecision;
import ironwood.compiler.semantic.InvocationPlanningContext.AccessRequest;
import ironwood.compiler.semantic.InvocationPlanningContext.ConstructorLookup;
import ironwood.compiler.semantic.InvocationPlanningContext.ConversionRequest;
import ironwood.compiler.semantic.InvocationPlanningContext.FieldLookup;
import ironwood.compiler.semantic.InvocationPlanningContext.InferenceRequest;
import ironwood.compiler.semantic.InvocationPlanningContext.MethodLookup;
import ironwood.compiler.semantic.InvocationPlanningContext.ResolvedField;
import ironwood.compiler.semantic.InvocationPlanningContext.SelectionRequest;
import ironwood.compiler.semantic.InvocationPlanningContext.TypeUse;
import ironwood.compiler.semantic.InvocationPlanningResult.PlanningRejection;
import ironwood.compiler.ast.ArrayAccessExpression;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.AssignmentExpression;
import ironwood.compiler.ast.AssignmentOperator;
import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.BinaryOperator;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.BooleanLiteralExpression;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.CharacterLiteralExpression;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.FloatingLiteralExpression;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.InstanceOfExpression;
import ironwood.compiler.ast.IntegerLiteralExpression;
import ironwood.compiler.ast.InterfaceSuperExpression;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.NullLiteralExpression;
import ironwood.compiler.ast.QualifiedSuperConstructorExpression;
import ironwood.compiler.ast.QualifiedThisExpression;
import ironwood.compiler.ast.StringLiteralExpression;
import ironwood.compiler.ast.SuperExpression;
import ironwood.compiler.ast.SwitchExpression;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.ThisExpression;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UnaryOperator;
import ironwood.compiler.ast.UpdateExpression;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Side-effect-free expression typing and candidate-local invocation planning.
 *
 * <p>This class never emits IR and never commits diagnostics. Every overload candidate is probed
 * independently, including its recursively planned poly-expression arguments. Only the selected
 * plan is suitable for later lowering.</p>
 */
final class InvocationPlanner {
    private static final IrType STRING_TYPE = IrType.reference("ironwood.lang.String");

    private final InvocationPlanningContext context;

    InvocationPlanner(InvocationPlanningContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    InvocationPlanningResult<ExpressionTypePlan> plan(Expression expression) {
        return plan(expression, Optional.empty());
    }

    boolean requiresCallablePlanning(CallExpression expression) {
        if (!expression.typeArguments().isEmpty()) {
            return true;
        }
        InvocationPlanningResult<ReceiverPlan> receiver = expression.receiver().isEmpty()
                ? implicitReceiver(expression.span())
                : planReceiver(expression.receiver().orElseThrow());
        if (!receiver.isResolved()) {
            return false;
        }
        InvocationPlanningResult<List<InvocationCandidate>> candidates = context.methodCandidates(
                new MethodLookup(receiver.resolvedValue(), expression));
        return candidates.isResolved() && candidates.resolvedValue().stream()
                .anyMatch(candidate -> !candidate.callableTypeParameters().isEmpty());
    }

    InvocationPlanningResult<InvocationPlan> planConstructorDelegation(
            IrType targetType, List<CallableSymbol> constructors, List<Expression> arguments,
            List<TypeName> explicitCallableTypeArguments, SourceSpan span) {
        Objects.requireNonNull(targetType, "targetType");
        List<InvocationCandidate> candidates = constructors.stream()
                .map(constructor -> InvocationCandidate.constructorDelegation(
                        constructor, targetType))
                .toList();
        InvocationPlanningResult<List<IrType>> explicitArguments = resolveTypes(
                explicitCallableTypeArguments, TypeUse.EXPLICIT_CALLABLE_ARGUMENT);
        if (!explicitArguments.isResolved()) {
            return propagate(explicitArguments);
        }
        return planInvocation(InvocationKind.CONSTRUCTOR,
                new ReceiverPlan(ReceiverKind.CONSTRUCTION, targetType, Optional.empty()),
                Optional.empty(), Optional.empty(), candidates, arguments, List.of(), false,
                explicitArguments.resolvedValue(), Optional.empty(), span);
    }

    InvocationPlanningResult<ExpressionTypePlan> plan(Expression expression,
                                                       Optional<IrType> expectedType) {
        Objects.requireNonNull(expression, "expression");
        Optional<IrType> expected = expectedType == null ? Optional.empty() : expectedType;
        if (expression instanceof IntegerLiteralExpression literal) {
            IrType type = literal.text().endsWith("l") || literal.text().endsWith("L")
                    ? IrType.I64 : IrType.I32;
            return resolved(ExpressionTypePlan.simple(expression, type, expected));
        }
        if (expression instanceof FloatingLiteralExpression literal) {
            IrType type = literal.text().endsWith("f") || literal.text().endsWith("F")
                    ? IrType.F32 : IrType.F64;
            return resolved(ExpressionTypePlan.simple(expression, type, expected));
        }
        if (expression instanceof CharacterLiteralExpression) {
            return resolved(ExpressionTypePlan.simple(expression, IrType.U16, expected));
        }
        if (expression instanceof BooleanLiteralExpression) {
            return resolved(ExpressionTypePlan.simple(expression, IrType.I1, expected));
        }
        if (expression instanceof NullLiteralExpression) {
            return resolved(ExpressionTypePlan.simple(expression, IrType.NULL, expected));
        }
        if (expression instanceof StringLiteralExpression) {
            return resolved(ExpressionTypePlan.simple(expression, STRING_TYPE, expected));
        }
        if (expression instanceof ThisExpression thisExpression) {
            return specialValue(expression, expected,
                    context.currentThisType(thisExpression.span()));
        }
        if (expression instanceof QualifiedThisExpression qualifiedThis) {
            return specialValue(expression, expected,
                    context.qualifiedThisType(qualifiedThis));
        }
        if (expression instanceof SuperExpression superExpression) {
            return specialValue(expression, expected, context.superType(superExpression.span()));
        }
        if (expression instanceof InterfaceSuperExpression interfaceSuper) {
            return specialValue(expression, expected,
                    context.interfaceSuperType(interfaceSuper));
        }
        if (expression instanceof NameExpression nameExpression) {
            return planName(nameExpression, expected);
        }
        if (expression instanceof FieldAccessExpression fieldAccess) {
            return planFieldAccess(fieldAccess, expected);
        }
        if (expression instanceof ArrayAccessExpression arrayAccess) {
            return planArrayAccess(arrayAccess, expected);
        }
        if (expression instanceof ArrayCreationExpression arrayCreation) {
            return planArrayCreation(arrayCreation, expected);
        }
        if (expression instanceof ArrayInitializerExpression arrayInitializer) {
            return planArrayInitializer(arrayInitializer, expected);
        }
        if (expression instanceof CastExpression castExpression) {
            return planCast(castExpression, expected);
        }
        if (expression instanceof InstanceOfExpression instanceOfExpression) {
            return planInstanceOf(instanceOfExpression, expected);
        }
        if (expression instanceof UnaryExpression unaryExpression) {
            return planUnary(unaryExpression, expected);
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            return planBinary(binaryExpression, expected);
        }
        if (expression instanceof AssignmentExpression assignmentExpression) {
            return planAssignment(assignmentExpression, expected);
        }
        if (expression instanceof UpdateExpression updateExpression) {
            return planUpdate(updateExpression, expected);
        }
        if (expression instanceof ConditionalExpression conditionalExpression) {
            return planConditional(conditionalExpression, expected);
        }
        if (expression instanceof SwitchExpression switchExpression) {
            return planSwitchExpression(switchExpression, expected);
        }
        if (expression instanceof CallExpression callExpression) {
            return planCall(callExpression, expected);
        }
        if (expression instanceof NewExpression newExpression) {
            return planNew(newExpression, expected);
        }
        if (expression instanceof QualifiedSuperConstructorExpression constructorExpression) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.UNSUPPORTED_EXPRESSION,
                    "qualified superclass construction must be normalized before expression planning",
                    constructorExpression.span()));
        }
        return rejected(PlanningRejection.of(PlanningRejection.Code.UNSUPPORTED_EXPRESSION,
                "expression is not supported by type-only invocation planning", expression.span()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planName(
            NameExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<IrType> lexical = context.lexicalNameType(expression);
        if (lexical.isResolved()) {
            return resolved(ExpressionTypePlan.simple(expression, lexical.resolvedValue(), expected));
        }
        if (lexical.isRejected()) {
            return rejected(lexical.rejections());
        }
        InvocationPlanningResult<IrType> qualifier = context.resolveTypeQualifier(expression);
        if (qualifier.isResolved()) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.TYPE_USED_AS_VALUE,
                    "type '" + qualifier.resolvedValue().displayName()
                            + "' cannot be used as a value", expression.span()));
        }
        if (qualifier.isRejected()) {
            return rejected(qualifier.rejections());
        }
        return rejected(PlanningRejection.of(PlanningRejection.Code.UNRESOLVED_NAME,
                "cannot resolve value '" + expression.name() + "'", expression.span()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planFieldAccess(
            FieldAccessExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<ReceiverPlan> receiverResult = planReceiver(expression.receiver());
        if (!receiverResult.isResolved()) {
            return propagate(receiverResult);
        }
        ReceiverPlan receiver = receiverResult.resolvedValue();
        if (receiver.lookupType().isArray() && expression.fieldName().equals("length")) {
            if (receiver.kind() == ReceiverKind.TYPE) {
                return rejected(PlanningRejection.of(PlanningRejection.Code.WRONG_INVOCATION_FORM,
                        "array length requires an array value", expression.fieldNameSpan()));
            }
            return resolved(new ExpressionTypePlan(expression, IrType.I32, expected, false,
                    receiver.valuePlan().stream().toList(), Optional.empty()));
        }
        InvocationPlanningResult<ResolvedField> fieldResult = context.resolveField(
                new FieldLookup(receiver, expression));
        if (fieldResult.isNotFound()) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.UNRESOLVED_FIELD,
                    "cannot resolve field '" + expression.fieldName() + "' through type '"
                            + receiver.lookupType().displayName() + "'",
                    expression.fieldNameSpan()));
        }
        if (fieldResult.isRejected()) {
            return rejected(fieldResult.rejections());
        }
        ResolvedField field = fieldResult.resolvedValue();
        if (receiver.kind() == ReceiverKind.TYPE && !field.isStatic()) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.WRONG_INVOCATION_FORM,
                    "instance field '" + expression.fieldName()
                            + "' cannot be accessed through a type", expression.fieldNameSpan()));
        }
        return resolved(new ExpressionTypePlan(expression, field.type(), expected, false,
                receiver.valuePlan().stream().toList(), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planArrayAccess(
            ArrayAccessExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<ExpressionTypePlan> array = plan(expression.array(), Optional.empty());
        InvocationPlanningResult<ExpressionTypePlan> index = plan(expression.index(),
                Optional.of(IrType.I32));
        List<PlanningRejection> failures = failures(array, index);
        if (!failures.isEmpty()) {
            return rejected(failures);
        }
        if (!array.resolvedValue().type().isArray()) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_ARRAY_OPERATION,
                    "array access requires an array, not '"
                            + array.resolvedValue().type().displayName() + "'",
                    expression.array().span()));
        }
        if (!index.resolvedValue().type().equals(IrType.I32)) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_ARRAY_OPERATION,
                    "array index must have type int, not '"
                            + index.resolvedValue().type().displayName() + "'",
                    expression.index().span()));
        }
        return resolved(new ExpressionTypePlan(expression,
                array.resolvedValue().type().elementType(), expected, false,
                List.of(array.resolvedValue(), index.resolvedValue()), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planArrayCreation(
            ArrayCreationExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<IrType> element = context.resolveType(expression.elementType(),
                TypeUse.ARRAY_ELEMENT);
        if (!element.isResolved()) {
            return require(element, PlanningRejection.Code.UNRESOLVED_TYPE,
                    "cannot resolve array element type", expression.elementType().span());
        }
        IrType arrayType = IrType.array(element.resolvedValue());
        if (expression.initializer().isPresent()) {
            InvocationPlanningResult<ExpressionTypePlan> initializer = planArrayInitializer(
                    expression.initializer().orElseThrow(), Optional.of(arrayType));
            if (!initializer.isResolved()) {
                return propagate(initializer);
            }
            return resolved(new ExpressionTypePlan(expression, arrayType, expected, false,
                    List.of(initializer.resolvedValue()), Optional.empty()));
        }
        Expression lengthExpression = expression.length().orElseThrow();
        InvocationPlanningResult<ExpressionTypePlan> length = plan(lengthExpression,
                Optional.of(IrType.I32));
        if (!length.isResolved()) {
            return propagate(length);
        }
        if (!length.resolvedValue().type().equals(IrType.I32)) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_ARRAY_OPERATION,
                    "array length must have type int, not '"
                            + length.resolvedValue().type().displayName() + "'",
                    lengthExpression.span()));
        }
        return resolved(new ExpressionTypePlan(expression, arrayType,
                expected, false, List.of(length.resolvedValue()), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planArrayInitializer(
            ArrayInitializerExpression expression, Optional<IrType> expected) {
        if (expected.isEmpty() || !expected.orElseThrow().isArray()) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_ARRAY_OPERATION,
                    "array initializer requires an array target type", expression.span()));
        }
        IrType arrayType = expected.orElseThrow();
        IrType elementType = arrayType.elementType();
        List<ExpressionTypePlan> elements = new ArrayList<>();
        List<PlanningRejection> failures = new ArrayList<>();
        for (Expression element : expression.elements()) {
            Optional<IrType> elementExpected = element instanceof ArrayInitializerExpression
                    && !elementType.isArray() ? Optional.empty() : Optional.of(elementType);
            InvocationPlanningResult<ExpressionTypePlan> planned = plan(element, elementExpected);
            if (planned.isResolved()) {
                elements.add(planned.resolvedValue());
            } else if (planned.isRejected()) {
                failures.addAll(planned.rejections());
            }
        }
        if (!failures.isEmpty()) {
            return rejected(failures);
        }
        return resolved(new ExpressionTypePlan(expression, arrayType, expected, false,
                elements, Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planCast(
            CastExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<IrType> target = context.resolveType(expression.targetType(),
                TypeUse.CAST);
        if (!target.isResolved()) {
            return require(target, PlanningRejection.Code.UNRESOLVED_TYPE,
                    "cannot resolve cast target type", expression.targetType().span());
        }
        InvocationPlanningResult<ExpressionTypePlan> operand = plan(expression.operand(),
                Optional.of(target.resolvedValue()));
        if (!operand.isResolved()) {
            return propagate(operand);
        }
        return resolved(new ExpressionTypePlan(expression, target.resolvedValue(), expected,
                false, List.of(operand.resolvedValue()), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planInstanceOf(
            InstanceOfExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<IrType> target = context.resolveType(expression.targetType(),
                TypeUse.INSTANCEOF);
        InvocationPlanningResult<ExpressionTypePlan> operand = plan(expression.operand(),
                Optional.empty());
        if (!target.isResolved()) {
            return require(target, PlanningRejection.Code.UNRESOLVED_TYPE,
                    "cannot resolve instanceof target type", expression.targetType().span());
        }
        if (!operand.isResolved()) {
            return propagate(operand);
        }
        if (!target.resolvedValue().isReference() || !operand.resolvedValue().type().isReference()) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_OPERAND,
                    "instanceof requires reference operand and target types",
                    expression.operatorSpan()));
        }
        return resolved(new ExpressionTypePlan(expression, IrType.I1, expected, false,
                List.of(operand.resolvedValue()), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planUnary(
            UnaryExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<ExpressionTypePlan> operand = plan(expression.operand(),
                Optional.empty());
        if (!operand.isResolved()) {
            return propagate(operand);
        }
        IrType operandType = operand.resolvedValue().type();
        IrType result;
        if (expression.operator() == UnaryOperator.NOT) {
            if (!operandType.equals(IrType.I1)) {
                return invalidOperand(expression.operatorSpan(), "logical not", operandType);
            }
            result = IrType.I1;
        } else if (expression.operator() == UnaryOperator.BITWISE_COMPLEMENT) {
            if (!operandType.isIntegral()) {
                return invalidOperand(expression.operatorSpan(), "bitwise complement", operandType);
            }
            result = PrimitiveConversions.unaryPromotion(operandType);
        } else {
            if (!operandType.isNumeric()) {
                return invalidOperand(expression.operatorSpan(), "unary numeric operator", operandType);
            }
            result = PrimitiveConversions.unaryPromotion(operandType);
        }
        return resolved(new ExpressionTypePlan(expression, result, expected, false,
                List.of(operand.resolvedValue()), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planBinary(
            BinaryExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<ExpressionTypePlan> left = plan(expression.left(), Optional.empty());
        InvocationPlanningResult<ExpressionTypePlan> right = plan(expression.right(), Optional.empty());
        List<PlanningRejection> failures = failures(left, right);
        if (!failures.isEmpty()) {
            return rejected(failures);
        }
        IrType leftType = left.resolvedValue().type();
        IrType rightType = right.resolvedValue().type();
        InvocationPlanningResult<IrType> result = binaryResult(expression.operator(), leftType,
                rightType, expression.operatorSpan());
        if (!result.isResolved()) {
            return propagate(result);
        }
        return resolved(new ExpressionTypePlan(expression, result.resolvedValue(), expected,
                false, List.of(left.resolvedValue(), right.resolvedValue()), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planAssignment(
            AssignmentExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<ExpressionTypePlan> target = plan(expression.target(),
                Optional.empty());
        if (!target.isResolved()) {
            return propagate(target);
        }
        IrType targetType = target.resolvedValue().type();
        InvocationPlanningResult<ExpressionTypePlan> value = plan(expression.value(),
                Optional.of(targetType));
        if (!value.isResolved()) {
            return propagate(value);
        }
        if (expression.operator() == AssignmentOperator.ASSIGN) {
            InvocationPlanningResult<InvocationConversion> conversion = context.classifyConversion(
                    new ConversionRequest(targetType, value.resolvedValue().type(),
                            ApplicabilityPhase.LOOSE_FIXED, expression.operatorSpan()));
            if (!conversion.isResolved()) {
                return require(conversion, PlanningRejection.Code.INVALID_ASSIGNMENT,
                        "value of type '" + value.resolvedValue().type().displayName()
                                + "' is not assignable to '" + targetType.displayName() + "'",
                        expression.operatorSpan());
            }
        } else {
            BinaryOperator binary = assignmentBinaryOperator(expression.operator());
            InvocationPlanningResult<IrType> operation = binaryResult(binary, targetType,
                    value.resolvedValue().type(), expression.operatorSpan());
            if (!operation.isResolved()) {
                return propagate(operation);
            }
            if (operation.resolvedValue().equals(STRING_TYPE) && !targetType.equals(STRING_TYPE)) {
                return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_ASSIGNMENT,
                        "value of type '" + operation.resolvedValue().displayName()
                                + "' is not assignable to '" + targetType.displayName() + "'",
                        expression.operatorSpan()));
            }
        }
        return resolved(new ExpressionTypePlan(expression, targetType, expected, false,
                List.of(target.resolvedValue(), value.resolvedValue()), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planUpdate(
            UpdateExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<ExpressionTypePlan> target = plan(expression.target(),
                Optional.empty());
        if (!target.isResolved()) {
            return propagate(target);
        }
        if (!target.resolvedValue().type().isNumeric()) {
            return invalidOperand(expression.operatorSpan(), "increment/decrement",
                    target.resolvedValue().type());
        }
        return resolved(new ExpressionTypePlan(expression, target.resolvedValue().type(), expected,
                false, List.of(target.resolvedValue()), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planConditional(
            ConditionalExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<ExpressionTypePlan> condition = plan(expression.condition(),
                Optional.of(IrType.I1));
        InvocationPlanningResult<ExpressionTypePlan> whenTrue = plan(expression.whenTrue(), expected);
        InvocationPlanningResult<ExpressionTypePlan> whenFalse = plan(expression.whenFalse(), expected);
        List<PlanningRejection> failures = failures(condition, whenTrue, whenFalse);
        if (!failures.isEmpty()) {
            return rejected(failures);
        }
        if (!condition.resolvedValue().type().equals(IrType.I1)) {
            return invalidOperand(expression.condition().span(), "conditional condition",
                    condition.resolvedValue().type());
        }
        IrType left = whenTrue.resolvedValue().type();
        IrType right = whenFalse.resolvedValue().type();
        Optional<IrType> type = conditionalResult(left, right);
        if (type.isEmpty()) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_OPERAND,
                    "conditional branches have incompatible types '" + left.displayName()
                            + "' and '" + right.displayName() + "'", expression.questionSpan()));
        }
        boolean poly = expected.isPresent()
                && (whenTrue.resolvedValue().polyExpression()
                || whenFalse.resolvedValue().polyExpression());
        return resolved(new ExpressionTypePlan(expression, type.orElseThrow(), expected, poly,
                List.of(condition.resolvedValue(), whenTrue.resolvedValue(),
                        whenFalse.resolvedValue()), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planSwitchExpression(
            SwitchExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<ExpressionTypePlan> selector = plan(expression.selector(),
                Optional.empty());
        if (!selector.isResolved()) {
            return propagate(selector);
        }
        List<Expression> resultExpressions = new ArrayList<>();
        if (expression.arrowRules()) {
            expression.rules().forEach(rule -> {
                if (rule.body() instanceof SwitchRuleExpression result) {
                    resultExpressions.add(result.expression());
                } else if (rule.body() instanceof SwitchRuleBlock block) {
                    collectYieldExpressions(block.block(), resultExpressions);
                }
            });
        } else {
            expression.groups().forEach(group -> group.statements()
                    .forEach(statement -> collectYieldExpressions(statement, resultExpressions)));
        }
        if (resultExpressions.isEmpty()) {
            IrType type = expected.filter(value -> !value.equals(IrType.VOID))
                    .orElse(IrType.I32);
            return resolved(new ExpressionTypePlan(expression, type, expected, false,
                    List.of(selector.resolvedValue()), Optional.empty()));
        }
        List<ExpressionTypePlan> children = new ArrayList<>();
        children.add(selector.resolvedValue());
        List<IrType> resultTypes = new ArrayList<>();
        for (Expression resultExpression : resultExpressions) {
            InvocationPlanningResult<ExpressionTypePlan> result = plan(resultExpression, expected);
            if (!result.isResolved()) {
                return propagate(result);
            }
            children.add(result.resolvedValue());
            resultTypes.add(result.resolvedValue().type());
        }
        IrType merged = resultTypes.getFirst();
        boolean expectedCompatible = expected.isPresent()
                && !expected.orElseThrow().equals(IrType.VOID);
        if (expectedCompatible) {
            for (IrType type : resultTypes) {
                InvocationPlanningResult<InvocationConversion> conversion =
                        context.classifyConversion(new ConversionRequest(
                                expected.orElseThrow(), type,
                                ApplicabilityPhase.LOOSE_FIXED, expression.span()));
                if (!conversion.isResolved()) {
                    expectedCompatible = false;
                    break;
                }
            }
        }
        if (expectedCompatible) {
            merged = expected.orElseThrow();
        } else {
            for (int index = 1; index < resultTypes.size(); index++) {
                Optional<IrType> next = conditionalResult(merged, resultTypes.get(index));
                if (next.isEmpty()) {
                    return rejected(PlanningRejection.of(
                            PlanningRejection.Code.INVALID_OPERAND,
                            "switch result branches have incompatible types '"
                                    + merged.displayName() + "' and '"
                                    + resultTypes.get(index).displayName() + "'",
                            expression.span()));
                }
                merged = next.orElseThrow();
            }
        }
        return resolved(new ExpressionTypePlan(expression, merged, expected, false,
                children, Optional.empty()));
    }

    private void collectYieldExpressions(Statement statement, List<Expression> results) {
        if (statement instanceof YieldStatement yielded) {
            results.add(yielded.value());
        } else if (statement instanceof Block block) {
            block.statements().forEach(child -> collectYieldExpressions(child, results));
        } else if (statement instanceof IfStatement conditional) {
            collectYieldExpressions(conditional.thenBranch(), results);
            conditional.elseBranch().ifPresent(branch ->
                    collectYieldExpressions(branch, results));
        } else if (statement instanceof TryStatement guarded) {
            collectYieldExpressions(guarded.body(), results);
            guarded.catches().forEach(caught ->
                    collectYieldExpressions(caught.body(), results));
            guarded.finallyBlock().ifPresent(cleanup ->
                    collectYieldExpressions(cleanup, results));
        } else if (statement instanceof WhileStatement loop) {
            collectYieldExpressions(loop.body(), results);
        } else if (statement instanceof DoWhileStatement loop) {
            collectYieldExpressions(loop.body(), results);
        } else if (statement instanceof ForStatement loop) {
            collectYieldExpressions(loop.body(), results);
        } else if (statement instanceof EnhancedForStatement loop) {
            collectYieldExpressions(loop.body(), results);
        } else if (statement instanceof LabeledStatement labeled) {
            collectYieldExpressions(labeled.body(), results);
        } else if (statement instanceof SwitchStatement switched) {
            switched.groups().forEach(group -> group.statements()
                    .forEach(child -> collectYieldExpressions(child, results)));
        } else if (statement instanceof ModernSwitchStatement switched) {
            switched.rules().forEach(rule -> {
                if (rule.body() instanceof SwitchRuleBlock block) {
                    collectYieldExpressions(block.block(), results);
                }
            });
        }
    }

    private InvocationPlanningResult<ExpressionTypePlan> planCall(
            CallExpression expression, Optional<IrType> expected) {
        InvocationPlanningResult<ReceiverPlan> receiver = expression.receiver().isEmpty()
                ? implicitReceiver(expression.span())
                : planReceiver(expression.receiver().orElseThrow());
        if (!receiver.isResolved()) {
            return propagate(receiver);
        }
        InvocationPlanningResult<List<InvocationCandidate>> candidates = context.methodCandidates(
                new MethodLookup(receiver.resolvedValue(), expression));
        if (!candidates.isResolved()) {
            return require(candidates, PlanningRejection.Code.NO_CANDIDATES,
                    "cannot resolve method '" + expression.methodName() + "'",
                    expression.methodNameSpan());
        }
        InvocationPlanningResult<List<IrType>> explicitArguments = resolveTypes(
                expression.typeArguments(), TypeUse.EXPLICIT_CALLABLE_ARGUMENT);
        if (!explicitArguments.isResolved()) {
            return propagate(explicitArguments);
        }
        InvocationPlanningResult<InvocationPlan> invocation = planInvocation(
                InvocationKind.METHOD, receiver.resolvedValue(), Optional.empty(), Optional.empty(),
                candidates.resolvedValue(), expression.arguments(), List.of(), false,
                explicitArguments.resolvedValue(), expected, expression.span());
        if (!invocation.isResolved()) {
            return propagate(invocation);
        }
        InvocationPlan selected = invocation.resolvedValue();
        List<ExpressionTypePlan> operands = new ArrayList<>();
        selected.receiver().valuePlan().ifPresent(operands::add);
        selected.selected().arguments().stream().map(ArgumentPlan::expressionPlan)
                .forEach(operands::add);
        return resolved(new ExpressionTypePlan(expression, selected.resultType(), expected,
                selected.polyExpression(), operands, Optional.of(selected)));
    }

    private InvocationPlanningResult<ExpressionTypePlan> planNew(
            NewExpression expression, Optional<IrType> expected) {
        TypeUse targetUse = expression.diamond() ? TypeUse.DIAMOND_TARGET
                : TypeUse.CONSTRUCTION_TARGET;
        InvocationPlanningResult<InvocationPlanningContext.ConstructionTargetPlan> target =
                context.planConstructionTarget(expression,
                targetUse);
        if (!target.isResolved()) {
            return require(target, PlanningRejection.Code.UNRESOLVED_TYPE,
                    "cannot resolve construction target '" + expression.classType().displayName() + "'",
                    expression.classNameSpan());
        }
        ReceiverPlan receiver = new ReceiverPlan(ReceiverKind.CONSTRUCTION,
                target.resolvedValue().targetType(), Optional.empty());
        InvocationPlanningResult<List<InvocationCandidate>> candidates =
                context.constructorCandidates(new ConstructorLookup(
                        target.resolvedValue().targetType(), expression));
        if (!candidates.isResolved()) {
            return require(candidates, PlanningRejection.Code.NO_CANDIDATES,
                    "cannot resolve constructor for '" + expression.classType().displayName() + "'",
                    expression.classNameSpan());
        }
        InvocationPlanningResult<List<IrType>> callableArguments = resolveTypes(
                expression.constructorTypeArguments(), TypeUse.EXPLICIT_CALLABLE_ARGUMENT);
        if (!callableArguments.isResolved()) {
            return propagate(callableArguments);
        }
        List<IrType> classArguments = expression.diamond()
                || target.resolvedValue().classArgumentsBaked()
                ? List.of() : target.resolvedValue().targetType().typeArguments();
        InvocationPlanningResult<InvocationPlan> invocation = planInvocation(
                InvocationKind.CONSTRUCTOR, receiver, expression.enclosingInstance(),
                target.resolvedValue().enclosingInstance(),
                candidates.resolvedValue(), expression.arguments(), classArguments,
                expression.diamond(), callableArguments.resolvedValue(), expected,
                expression.span());
        if (!invocation.isResolved()) {
            return propagate(invocation);
        }
        InvocationPlan selected = invocation.resolvedValue();
        List<ExpressionTypePlan> operands = new ArrayList<>();
        selected.enclosingInstance().ifPresent(operands::add);
        selected.selected().arguments().stream().map(ArgumentPlan::expressionPlan)
                .forEach(operands::add);
        return resolved(new ExpressionTypePlan(expression, selected.resultType(), expected,
                selected.polyExpression(), operands, Optional.of(selected)));
    }

    private InvocationPlanningResult<InvocationPlan> planInvocation(
            InvocationKind kind, ReceiverPlan receiver, Optional<Expression> enclosingExpression,
            Optional<ExpressionTypePlan> preplannedEnclosing,
            List<InvocationCandidate> candidates, List<Expression> arguments,
            List<IrType> explicitClassArguments, boolean diamond,
            List<IrType> explicitCallableArguments, Optional<IrType> expected,
            SourceSpan span) {
        if (candidates.isEmpty()) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.NO_CANDIDATES,
                    "no invocation candidates were found", span));
        }
        List<CandidateRejection> rejectedCandidates = new ArrayList<>();
        List<InvocationCandidate> accessible = new ArrayList<>();
        for (InvocationCandidate candidate : candidates) {
            if (candidate.isConstructor() != (kind == InvocationKind.CONSTRUCTOR)) {
                rejectedCandidates.add(rejection(candidate, ApplicabilityPhase.STRICT_FIXED,
                        PlanningRejection.of(PlanningRejection.Code.WRONG_INVOCATION_FORM,
                                "candidate has the wrong callable kind", span)));
                continue;
            }
            AccessDecision access = context.checkAccess(new AccessRequest(candidate, receiver,
                    kind, span));
            if (!access.accessible()) {
                rejectedCandidates.add(rejection(candidate, ApplicabilityPhase.STRICT_FIXED,
                        access.rejection().orElseThrow().forCandidate(candidate)));
                continue;
            }
            accessible.add(candidate);
        }
        for (ApplicabilityPhase phase : ApplicabilityPhase.values()) {
            List<CandidatePlan> applicable = new ArrayList<>();
            for (InvocationCandidate candidate : accessible) {
                Map<String, TypeVariableSymbol> captureCheckpoint =
                        context.plannedCaptures();
                InvocationPlanningResult<CandidatePlan> plan;
                try {
                    plan = planCandidate(candidate, phase,
                            enclosingExpression, preplannedEnclosing, arguments,
                            explicitClassArguments, diamond,
                            explicitCallableArguments, expected, span);
                    if (plan.isResolved()) {
                        plan = resolved(plan.resolvedValue().withPlannedCaptures(
                                context.plannedCaptures()));
                    }
                } finally {
                    context.restorePlannedCaptures(captureCheckpoint);
                }
                if (plan.isResolved()) {
                    applicable.add(plan.resolvedValue());
                } else {
                    List<PlanningRejection> reasons = plan.isRejected() ? plan.rejections()
                            : List.of(PlanningRejection.of(PlanningRejection.Code.NO_CANDIDATES,
                            "candidate did not produce an applicability decision", span));
                    rejectedCandidates.add(new CandidateRejection(candidate, phase,
                            reasons.stream().map(reason -> reason.forCandidate(candidate)).toList()));
                }
            }
            if (applicable.isEmpty()) {
                continue;
            }
            Map<String, TypeVariableSymbol> selectionCheckpoint = context.plannedCaptures();
            InvocationPlanningResult<CandidatePlan> selection;
            try {
                applicable.stream().map(CandidatePlan::plannedCaptures)
                        .forEach(context::mergePlannedCaptures);
                selection = context.selectMostSpecific(
                        new SelectionRequest(kind, receiver, phase, applicable, span));
            } finally {
                context.restorePlannedCaptures(selectionCheckpoint);
            }
            if (!selection.isResolved()) {
                return require(selection, PlanningRejection.Code.AMBIGUOUS_INVOCATION,
                        "invocation has no unique most-specific candidate", span);
            }
            CandidatePlan selected = selection.resolvedValue();
            if (!applicable.contains(selected)) {
                return rejected(PlanningRejection.of(
                        PlanningRejection.Code.AMBIGUOUS_INVOCATION,
                        "most-specific callback selected a candidate outside the applicable set",
                        span));
            }
            context.mergePlannedCaptures(selected.plannedCaptures());
            return resolved(new InvocationPlan(kind, span, receiver,
                    selected.enclosingInstance(), selected, applicable,
                    rejectedCandidates, expected, selected.polyExpression()));
        }
        List<PlanningRejection> causes = rejectedCandidates.stream()
                .flatMap(rejection -> rejection.reasons().stream()).toList();
        return rejected(PlanningRejection.causedBy(PlanningRejection.Code.NO_CANDIDATES,
                "no accessible invocation candidate is applicable", span, causes));
    }

    private InvocationPlanningResult<CandidatePlan> planCandidate(
            InvocationCandidate candidate, ApplicabilityPhase phase,
            Optional<Expression> enclosingExpression,
            Optional<ExpressionTypePlan> preplannedEnclosing,
            List<Expression> arguments,
            List<IrType> explicitClassArguments, boolean diamond,
            List<IrType> explicitCallableArguments, Optional<IrType> expected,
            SourceSpan span) {
        InvocationPlanningResult<List<IrType>> effectiveTemplates = effectiveParameterTypes(
                candidate, arguments.size(), span);
        if (!effectiveTemplates.isResolved()) {
            return propagate(effectiveTemplates);
        }
        if (diamond && candidate.classTypeParameters().isEmpty()) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                    "diamond construction requires a generic class", span));
        }
        if (!diamond && explicitClassArguments.size() != candidate.classTypeParameters().size()) {
            return rejected(PlanningRejection.of(
                    PlanningRejection.Code.WRONG_TYPE_ARGUMENT_COUNT,
                    "class declares " + candidate.classTypeParameters().size()
                            + " type parameter(s) but received " + explicitClassArguments.size(), span));
        }
        if (!explicitCallableArguments.isEmpty()
                && !candidate.callableTypeParameters().isEmpty()
                && explicitCallableArguments.size() != candidate.callableTypeParameters().size()) {
            return rejected(PlanningRejection.of(
                    PlanningRejection.Code.WRONG_TYPE_ARGUMENT_COUNT,
                    "callable declares " + candidate.callableTypeParameters().size()
                            + " type parameter(s) but received "
                            + explicitCallableArguments.size(), span));
        }
        boolean needsInference = diamond || !candidate.classTypeParameters().isEmpty()
                || !candidate.callableTypeParameters().isEmpty();
        InferenceSolution solution;
        if (needsInference) {
            InvocationPlanningResult<InferenceSolution> inferred = context.infer(
                    new InferenceRequest(candidate, phase, effectiveTemplates.resolvedValue(),
                            arguments, explicitClassArguments, diamond,
                            explicitCallableArguments, expected, this::plan, span));
            if (!inferred.isResolved()) {
                return require(inferred, PlanningRejection.Code.INFERENCE_FAILED,
                        "generic inference failed for candidate '" + candidate.identity() + "'",
                        span);
            }
            solution = inferred.resolvedValue();
            Optional<PlanningRejection> invalid = validateInferenceSolution(candidate, solution,
                    diamond, explicitClassArguments, explicitCallableArguments, span);
            if (invalid.isPresent()) {
                return rejected(invalid.orElseThrow());
            }
        } else {
            solution = InferenceSolution.empty();
        }
        context.mergePlannedCaptures(solution.plannedCaptures());
        Map<String, IrType> substitutions = solution.substitutions();
        List<IrType> effectiveParameters = effectiveTemplates.resolvedValue().stream()
                .map(type -> type.substitute(substitutions)).toList();
        List<ArgumentPlan> argumentPlans = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            Expression argument = arguments.get(index);
            IrType parameterType = effectiveParameters.get(index);
            InvocationPlanningResult<ExpressionTypePlan> expressionPlan = plan(argument,
                    Optional.of(parameterType));
            if (!expressionPlan.isResolved()) {
                List<PlanningRejection> causes = expressionPlan.isRejected()
                        ? expressionPlan.rejections() : List.of();
                return rejected(PlanningRejection.causedBy(
                        PlanningRejection.Code.ARGUMENT_REJECTED,
                        "argument " + (index + 1) + " cannot be typed for parameter '"
                                + parameterType.displayName() + "'", argument.span(), causes));
            }
            IrType conversionSource = solution.conversionSourceType(index,
                    expressionPlan.resolvedValue().type());
            InvocationPlanningResult<InvocationConversion> conversion =
                    context.classifyConversion(new ConversionRequest(parameterType,
                            conversionSource, phase, argument.span()));
            if (!conversion.isResolved()) {
                return require(conversion, PlanningRejection.Code.CONVERSION_REJECTED,
                        "argument " + (index + 1) + " of type '"
                                + conversionSource.displayName()
                                + "' is not convertible to '" + parameterType.displayName() + "'",
                        argument.span());
            }
            argumentPlans.add(new ArgumentPlan(argument, parameterType,
                    expressionPlan.resolvedValue(), conversion.resolvedValue()));
        }
        InvocationPlanningResult<Optional<ExpressionTypePlan>> enclosing = planEnclosingInstance(
                candidate, enclosingExpression, preplannedEnclosing,
                substitutions, phase, span);
        if (!enclosing.isResolved()) {
            return propagate(enclosing);
        }
        IrType resultType = candidate.resultTypeTemplate().substitute(substitutions);
        boolean poly = expected.isPresent()
                && (diamond || !candidate.callableTypeParameters().isEmpty());
        return resolved(new CandidatePlan(candidate, phase, solution, effectiveParameters,
                argumentPlans, enclosing.resolvedValue(), resultType, poly));
    }

    private InvocationPlanningResult<Optional<ExpressionTypePlan>> planEnclosingInstance(
            InvocationCandidate candidate, Optional<Expression> expression,
            Optional<ExpressionTypePlan> preplanned,
            Map<String, IrType> substitutions, ApplicabilityPhase phase, SourceSpan span) {
        Optional<IrType> required = candidate.enclosingInstanceTypeTemplate()
                .map(type -> type.substitute(substitutions));
        if (required.isEmpty() && expression.isEmpty() && preplanned.isEmpty()) {
            return resolved(Optional.empty());
        }
        if (required.isPresent() && expression.isEmpty() && preplanned.isEmpty()) {
            InvocationPlanningResult<IrType> implicit = context.implicitEnclosingInstanceType(
                    required.orElseThrow(), span);
            if (implicit.isResolved()) {
                return resolved(Optional.empty());
            }
            return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_CONSTRUCTION,
                    "constructor requires an enclosing instance", span));
        }
        if (required.isEmpty() && (expression.isPresent() || preplanned.isPresent())) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_CONSTRUCTION,
                    "constructor does not accept an enclosing instance", span));
        }
        ExpressionTypePlan enclosingPlan;
        if (preplanned.isPresent()) {
            enclosingPlan = preplanned.orElseThrow();
        } else {
            InvocationPlanningResult<ExpressionTypePlan> plan = plan(
                    expression.orElseThrow(), required);
            if (!plan.isResolved()) {
                return propagate(plan);
            }
            enclosingPlan = plan.resolvedValue();
        }
        InvocationPlanningResult<InvocationConversion> conversion = context.classifyConversion(
                new ConversionRequest(required.orElseThrow(), enclosingPlan.type(),
                        phase, enclosingPlan.expression().span()));
        if (!conversion.isResolved()) {
            return require(conversion, PlanningRejection.Code.INVALID_CONSTRUCTION,
                    "enclosing instance of type '" + enclosingPlan.type().displayName()
                            + "' is not convertible to '" + required.orElseThrow().displayName() + "'",
                    enclosingPlan.expression().span());
        }
        return resolved(Optional.of(enclosingPlan));
    }

    private Optional<PlanningRejection> validateInferenceSolution(
            InvocationCandidate candidate, InferenceSolution solution, boolean diamond,
            List<IrType> explicitClassArguments, List<IrType> explicitCallableArguments,
            SourceSpan span) {
        Map<String, IrType> classArguments = solution.classArguments();
        Map<String, IrType> callableArguments = solution.callableArguments();
        for (int index = 0; index < candidate.classTypeParameters().size(); index++) {
            CallableTypeParameter parameter = candidate.classTypeParameters().get(index);
            IrType actual = classArguments.get(parameter.id());
            if (actual == null) {
                return Optional.of(PlanningRejection.of(
                        PlanningRejection.Code.INFERENCE_FAILED,
                        "inference did not instantiate class type parameter '"
                                + parameter.displayName() + "'", span));
            }
            if (!diamond && !actual.equals(explicitClassArguments.get(index))) {
                return Optional.of(PlanningRejection.of(
                        PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "inference changed explicit class type argument '"
                                + explicitClassArguments.get(index).displayName() + "'", span));
            }
        }
        for (int index = 0; index < candidate.callableTypeParameters().size(); index++) {
            CallableTypeParameter parameter = candidate.callableTypeParameters().get(index);
            IrType actual = callableArguments.get(parameter.id());
            if (actual == null) {
                return Optional.of(PlanningRejection.of(
                        PlanningRejection.Code.INFERENCE_FAILED,
                        "inference did not instantiate callable type parameter '"
                                + parameter.displayName() + "'", span));
            }
            if (!explicitCallableArguments.isEmpty()
                    && !actual.equals(explicitCallableArguments.get(index))) {
                return Optional.of(PlanningRejection.of(
                        PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "inference changed explicit callable type argument '"
                                + explicitCallableArguments.get(index).displayName() + "'", span));
            }
        }
        if (classArguments.size() != candidate.classTypeParameters().size()
                || callableArguments.size() != candidate.callableTypeParameters().size()) {
            return Optional.of(PlanningRejection.of(PlanningRejection.Code.INFERENCE_FAILED,
                    "inference returned substitutions outside the candidate-local variable set",
                    span));
        }
        return Optional.empty();
    }

    private InvocationPlanningResult<List<IrType>> effectiveParameterTypes(
            InvocationCandidate candidate, int argumentCount, SourceSpan span) {
        List<IrType> declared = candidate.parameterTypeTemplates();
        if (declared.size() != argumentCount) {
            return rejected(PlanningRejection.of(PlanningRejection.Code.WRONG_ARITY,
                    "candidate requires " + declared.size() + " argument(s) but received "
                            + argumentCount, span));
        }
        return resolved(declared);
    }

    private InvocationPlanningResult<ReceiverPlan> implicitReceiver(SourceSpan span) {
        InvocationPlanningResult<IrType> type = context.implicitReceiverType(span);
        if (!type.isResolved()) {
            return require(type, PlanningRejection.Code.INVALID_RECEIVER,
                    "no implicit invocation receiver is available", span);
        }
        return resolved(new ReceiverPlan(ReceiverKind.IMPLICIT, type.resolvedValue(),
                Optional.empty()));
    }

    private InvocationPlanningResult<ReceiverPlan> planReceiver(Expression expression) {
        if (expression instanceof SuperExpression superExpression) {
            return specialReceiver(ReceiverKind.SUPER,
                    context.superType(superExpression.span()), superExpression.span());
        }
        if (expression instanceof InterfaceSuperExpression interfaceSuper) {
            return specialReceiver(ReceiverKind.INTERFACE_SUPER,
                    context.interfaceSuperType(interfaceSuper), interfaceSuper.span());
        }
        if (expression instanceof NameExpression name) {
            InvocationPlanningResult<IrType> lexical = context.lexicalNameType(name);
            if (lexical.isResolved()) {
                ExpressionTypePlan value = ExpressionTypePlan.simple(name,
                        lexical.resolvedValue(), Optional.empty());
                return resolved(new ReceiverPlan(ReceiverKind.INSTANCE, value.type(),
                        Optional.of(value)));
            }
            if (lexical.isRejected()) {
                return rejected(lexical.rejections());
            }
            InvocationPlanningResult<IrType> qualifier = context.resolveTypeQualifier(name);
            if (qualifier.isResolved()) {
                return resolved(new ReceiverPlan(ReceiverKind.TYPE, qualifier.resolvedValue(),
                        Optional.empty()));
            }
            if (qualifier.isRejected()) {
                return rejected(qualifier.rejections());
            }
            return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_RECEIVER,
                    "cannot resolve receiver '" + name.name() + "'", name.span()));
        }
        InvocationPlanningResult<ExpressionTypePlan> value = plan(expression, Optional.empty());
        if (value.isResolved()) {
            return resolved(new ReceiverPlan(ReceiverKind.INSTANCE,
                    value.resolvedValue().type(), Optional.of(value.resolvedValue())));
        }
        if (mayFallbackToType(value)) {
            InvocationPlanningResult<IrType> qualifier = context.resolveTypeQualifier(expression);
            if (qualifier.isResolved()) {
                return resolved(new ReceiverPlan(ReceiverKind.TYPE, qualifier.resolvedValue(),
                        Optional.empty()));
            }
            if (qualifier.isRejected()) {
                return rejected(qualifier.rejections());
            }
        }
        return propagate(value);
    }

    private InvocationPlanningResult<ReceiverPlan> specialReceiver(
            ReceiverKind kind, InvocationPlanningResult<IrType> type, SourceSpan span) {
        if (!type.isResolved()) {
            return require(type, PlanningRejection.Code.INVALID_RECEIVER,
                    "special receiver is not available in this context", span);
        }
        return resolved(new ReceiverPlan(kind, type.resolvedValue(), Optional.empty()));
    }

    private InvocationPlanningResult<ExpressionTypePlan> specialValue(
            Expression expression, Optional<IrType> expected,
            InvocationPlanningResult<IrType> type) {
        if (!type.isResolved()) {
            return require(type, PlanningRejection.Code.INVALID_RECEIVER,
                    "receiver expression is not available in this context", expression.span());
        }
        return resolved(ExpressionTypePlan.simple(expression, type.resolvedValue(), expected));
    }

    private InvocationPlanningResult<List<IrType>> resolveTypes(List<TypeName> typeNames,
                                                                 TypeUse use) {
        List<IrType> types = new ArrayList<>();
        List<PlanningRejection> failures = new ArrayList<>();
        for (TypeName typeName : typeNames) {
            InvocationPlanningResult<IrType> resolved = context.resolveType(typeName, use);
            if (resolved.isResolved()) {
                types.add(resolved.resolvedValue());
            } else if (resolved.isRejected()) {
                failures.addAll(resolved.rejections());
            } else {
                failures.add(PlanningRejection.of(PlanningRejection.Code.UNRESOLVED_TYPE,
                        "cannot resolve type '" + typeName.displayName() + "'", typeName.span()));
            }
        }
        return failures.isEmpty() ? resolved(List.copyOf(types)) : rejected(failures);
    }

    private InvocationPlanningResult<IrType> binaryResult(BinaryOperator operator,
                                                           IrType left, IrType right,
                                                           SourceSpan span) {
        if (operator == BinaryOperator.ADD && (left.equals(STRING_TYPE) || right.equals(STRING_TYPE))) {
            return resolved(STRING_TYPE);
        }
        if (operator == BinaryOperator.ADD || operator == BinaryOperator.SUBTRACT
                || operator == BinaryOperator.MULTIPLY || operator == BinaryOperator.DIVIDE
                || operator == BinaryOperator.REMAINDER) {
            return left.isNumeric() && right.isNumeric()
                    ? resolved(PrimitiveConversions.binaryPromotion(left, right))
                    : invalidBinary(operator, left, right, span);
        }
        if (operator == BinaryOperator.SHIFT_LEFT || operator == BinaryOperator.SHIFT_RIGHT
                || operator == BinaryOperator.UNSIGNED_SHIFT_RIGHT) {
            return left.isIntegral() && right.isIntegral()
                    ? resolved(PrimitiveConversions.unaryPromotion(left))
                    : invalidBinary(operator, left, right, span);
        }
        if (operator == BinaryOperator.BITWISE_AND || operator == BinaryOperator.BITWISE_XOR
                || operator == BinaryOperator.BITWISE_OR) {
            if (left.equals(IrType.I1) && right.equals(IrType.I1)) {
                return resolved(IrType.I1);
            }
            return left.isIntegral() && right.isIntegral()
                    ? resolved(PrimitiveConversions.binaryPromotion(left, right))
                    : invalidBinary(operator, left, right, span);
        }
        if (operator == BinaryOperator.LOGICAL_AND || operator == BinaryOperator.LOGICAL_OR) {
            return left.equals(IrType.I1) && right.equals(IrType.I1)
                    ? resolved(IrType.I1) : invalidBinary(operator, left, right, span);
        }
        if (operator == BinaryOperator.LESS || operator == BinaryOperator.LESS_EQUAL
                || operator == BinaryOperator.GREATER || operator == BinaryOperator.GREATER_EQUAL) {
            return left.isNumeric() && right.isNumeric()
                    ? resolved(IrType.I1) : invalidBinary(operator, left, right, span);
        }
        if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL) {
            boolean comparable = (left.isNumeric() && right.isNumeric())
                    || (left.equals(IrType.I1) && right.equals(IrType.I1))
                    || (left.isReference() && right.equals(IrType.NULL))
                    || (right.isReference() && left.equals(IrType.NULL))
                    || (left.isReference() && right.isReference()
                    && (context.leastUpperBound(left, right).isPresent()
                    || left.equals(right)));
            return comparable ? resolved(IrType.I1) : invalidBinary(operator, left, right, span);
        }
        return invalidBinary(operator, left, right, span);
    }

    private Optional<IrType> conditionalResult(IrType left, IrType right) {
        if (left.equals(right)) {
            return Optional.of(left);
        }
        if (left.isNumeric() && right.isNumeric()) {
            return Optional.of(PrimitiveConversions.binaryPromotion(left, right));
        }
        if (left.equals(IrType.NULL) && right.isReference()) {
            return Optional.of(right);
        }
        if (right.equals(IrType.NULL) && left.isReference()) {
            return Optional.of(left);
        }
        if (left.isReference() && right.isReference()) {
            return context.leastUpperBound(left, right);
        }
        return Optional.empty();
    }

    private static AssignmentOperator checked(AssignmentOperator operator) {
        return Objects.requireNonNull(operator, "operator");
    }

    private static BinaryOperator assignmentBinaryOperator(AssignmentOperator operator) {
        return switch (checked(operator)) {
            case ASSIGN -> throw new IllegalArgumentException("plain assignment has no binary operator");
            case ADD -> BinaryOperator.ADD;
            case SUBTRACT -> BinaryOperator.SUBTRACT;
            case MULTIPLY -> BinaryOperator.MULTIPLY;
            case DIVIDE -> BinaryOperator.DIVIDE;
            case REMAINDER -> BinaryOperator.REMAINDER;
            case SHIFT_LEFT -> BinaryOperator.SHIFT_LEFT;
            case SHIFT_RIGHT -> BinaryOperator.SHIFT_RIGHT;
            case UNSIGNED_SHIFT_RIGHT -> BinaryOperator.UNSIGNED_SHIFT_RIGHT;
            case BITWISE_AND -> BinaryOperator.BITWISE_AND;
            case BITWISE_XOR -> BinaryOperator.BITWISE_XOR;
            case BITWISE_OR -> BinaryOperator.BITWISE_OR;
        };
    }

    private static boolean mayFallbackToType(InvocationPlanningResult<?> result) {
        return result.isNotFound() || result.rejections().stream().allMatch(rejection ->
                rejection.code() == PlanningRejection.Code.UNRESOLVED_NAME
                        || rejection.code() == PlanningRejection.Code.UNRESOLVED_FIELD
                        || rejection.code() == PlanningRejection.Code.TYPE_USED_AS_VALUE);
    }

    private static CandidateRejection rejection(InvocationCandidate candidate,
                                                ApplicabilityPhase phase,
                                                PlanningRejection reason) {
        return new CandidateRejection(candidate, phase, List.of(reason.forCandidate(candidate)));
    }

    private static List<PlanningRejection> failures(
            InvocationPlanningResult<?>... results) {
        List<PlanningRejection> failures = new ArrayList<>();
        for (InvocationPlanningResult<?> result : results) {
            if (result.isRejected()) {
                failures.addAll(result.rejections());
            } else if (result.isNotFound()) {
                throw new IllegalStateException("required expression planning returned NOT_FOUND");
            }
        }
        return List.copyOf(failures);
    }

    private static <T> InvocationPlanningResult<T> resolved(T value) {
        return InvocationPlanningResult.resolved(value);
    }

    private static <T> InvocationPlanningResult<T> rejected(PlanningRejection rejection) {
        return InvocationPlanningResult.rejected(rejection);
    }

    private static <T> InvocationPlanningResult<T> rejected(List<PlanningRejection> rejections) {
        return InvocationPlanningResult.rejected(rejections);
    }

    private static <T> InvocationPlanningResult<T> propagate(
            InvocationPlanningResult<?> result) {
        if (result.isRejected()) {
            return rejected(result.rejections());
        }
        throw new IllegalStateException("cannot propagate a resolved or absent planning result");
    }

    private static <T> InvocationPlanningResult<T> require(
            InvocationPlanningResult<?> result, PlanningRejection.Code code,
            String fallback, SourceSpan span) {
        if (result.isRejected()) {
            return rejected(result.rejections());
        }
        if (result.isNotFound()) {
            return rejected(PlanningRejection.of(code, fallback, span));
        }
        throw new IllegalStateException("required planning result is already resolved");
    }

    private static <T> InvocationPlanningResult<T> invalidOperand(
            SourceSpan span, String operation, IrType type) {
        return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_OPERAND,
                operation + " does not accept operand type '" + type.displayName() + "'", span));
    }

    private static InvocationPlanningResult<IrType> invalidBinary(
            BinaryOperator operator, IrType left, IrType right, SourceSpan span) {
        return rejected(PlanningRejection.of(PlanningRejection.Code.INVALID_OPERAND,
                "operator " + operator + " does not accept operand types '"
                        + left.displayName() + "' and '" + right.displayName() + "'", span));
    }
}
