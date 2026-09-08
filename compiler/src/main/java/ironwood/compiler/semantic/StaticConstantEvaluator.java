// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.BinaryOperator;
import ironwood.compiler.ast.BooleanLiteralExpression;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.CharacterLiteralExpression;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.FloatingLiteralExpression;
import ironwood.compiler.ast.IntegerLiteralExpression;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NullLiteralExpression;
import ironwood.compiler.ast.StringLiteralExpression;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UnaryOperator;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrConstant;
import ironwood.compiler.ir.IrEnumConstant;
import ironwood.compiler.ir.IrImmortalObject;
import ironwood.compiler.ir.IrNull;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrStaticField;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceSpan;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** Separates constant variables from D055 runtime static initialization actions. */
final class StaticConstantEvaluator {
    private static final IrType STRING_TYPE = IrType.reference("ironwood.lang.String");

    private final Map<String, TypeSymbol> types;
    private final ClassHierarchy hierarchy;
    private final StaticImportResolver staticImports;
    private final List<Diagnostic> diagnostics;
    private final StringPool stringPool;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, ConstantValue> constants = new LinkedHashMap<>();
    private final Map<String, Integer> declarationIndexes = new LinkedHashMap<>();

    StaticConstantEvaluator(Map<String, TypeSymbol> types, ClassHierarchy hierarchy,
                            List<Diagnostic> diagnostics, StringPool stringPool) {
        this.types = types;
        this.hierarchy = hierarchy;
        this.staticImports = new StaticImportResolver(hierarchy.typeResolver(), hierarchy);
        this.diagnostics = diagnostics;
        this.stringPool = stringPool;
        for (TypeSymbol type : types.values()) {
            int index = 0;
            List<ironwood.compiler.ast.FieldDeclaration> declarations =
                    type.declaration() instanceof ironwood.compiler.ast.ClassDeclaration cls
                            ? cls.fields()
                            : ((ironwood.compiler.ast.InterfaceDeclaration) type.declaration()).fields();
            for (var declaration : declarations) {
                FieldSymbol field = type.declaredFields().get(declaration.name());
                if (field != null) {
                    declarationIndexes.put(key(field), index);
                }
                index++;
            }
        }
    }

    List<IrStaticField> evaluate() {
        List<IrStaticField> globals = new ArrayList<>();
        for (TypeSymbol owner : types.values()) {
            List<FieldSymbol> fields = List.copyOf(owner.declaredFields().values());
            for (FieldSymbol field : fields) {
                if (!field.isStatic()) {
                    continue;
                }
                boolean intrinsic = isStandardStream(owner, field);
                boolean constantCandidate = isCompileTimeConstant(owner, field,
                        new LinkedHashSet<>());
                ConstantValue value = constantCandidate ? evaluateField(owner, field) : null;
                if (value == null) {
                    value = defaultValue(field.type());
                }
                boolean compileTimeConstant = constantCandidate
                        && states.get(key(field)) == State.DONE;
                TypeSymbol.EnumConstantSymbol enumConstant = owner
                        .enumConstant(field.declaration().name()).orElse(null);
                IrOperand operand;
                if (intrinsic) {
                    String name = field.declaration().name();
                    operand = name.equals("in")
                            ? new IrImmortalObject("ironwood.lang.System.in", field.type(),
                                    IrType.reference("ironwood.io.StandardInputStream"), Map.of(),
                                    field.declaration().span())
                            : new IrImmortalObject("ironwood.lang.System." + name, field.type(),
                            Map.of("channel", new IrConstant(IrType.I32,
                                    name.equals("err") ? 2 : 1, field.declaration().span())),
                            field.declaration().span());
                } else if (enumConstant != null) {
                    operand = new IrEnumConstant(
                            owner.name() + "." + enumConstant.constant().name(), owner.selfType(),
                            enumConstant.concreteType().selfType(),
                            enumConstant.constant().name(), enumConstant.ordinal(),
                            stringPool.intern(enumConstant.constant().name(),
                                    enumConstant.constant().nameSpan()),
                            enumConstant.constant().span());
                } else {
                    operand = value.operand(field.type(), field.declaration().span());
                }
                IrStaticField global = new IrStaticField(owner.name(), field.declaration().name(),
                        field.type(), field.isFinal(), compileTimeConstant, intrinsic,
                        operand, field.declaration().span());
                ConstantValue exported = compileTimeConstant ? value : null;
                owner.replaceField(field.declaration().name(), field.withStaticField(global, exported));
                globals.add(global);
            }
        }
        return List.copyOf(globals);
    }

    private boolean isCompileTimeConstant(TypeSymbol owner, FieldSymbol field,
                                          Set<String> active) {
        if (!field.isStatic() || !field.isFinal() || !isConstantType(field.type())
                || field.declaration().initializer().isEmpty()) {
            return false;
        }
        String fieldKey = key(field);
        if (!active.add(fieldKey)) {
            // Let the evaluator produce the existing precise cycle diagnostic.
            return true;
        }
        boolean result = isConstantExpression(owner, field,
                field.declaration().initializer().orElseThrow(), active);
        active.remove(fieldKey);
        return result;
    }

    private boolean isConstantExpression(TypeSymbol owner, FieldSymbol target,
                                         Expression expression, Set<String> active) {
        if (expression instanceof BooleanLiteralExpression
                || expression instanceof CharacterLiteralExpression
                || expression instanceof IntegerLiteralExpression
                || expression instanceof FloatingLiteralExpression
                || expression instanceof StringLiteralExpression) {
            return true;
        }
        if (expression instanceof UnaryExpression unary) {
            return isConstantExpression(owner, target, unary.operand(), active);
        }
        if (expression instanceof BinaryExpression binary) {
            return isConstantExpression(owner, target, binary.left(), active)
                    && isConstantExpression(owner, target, binary.right(), active);
        }
        if (expression instanceof ConditionalExpression conditional) {
            return isConstantExpression(owner, target, conditional.condition(), active)
                    && isConstantExpression(owner, target, conditional.whenTrue(), active)
                    && isConstantExpression(owner, target, conditional.whenFalse(), active);
        }
        if (expression instanceof CastExpression cast) {
            return isConstantExpression(owner, target, cast.operand(), active);
        }
        if (expression instanceof NameExpression name) {
            ClassHierarchy.FieldResolution resolution = hierarchy.resolveField(
                    owner.selfType(), name.name());
            FieldSymbol referenced = resolution.ambiguous()
                    ? null : resolution.selected().orElse(null);
            if (referenced == null && resolution.candidates().isEmpty()) {
                List<FieldSymbol> imported = staticImports.fields(owner.unit(), name.name());
                referenced = imported.size() == 1 ? imported.getFirst() : null;
            }
            if (referenced == null || !referenced.isStatic() || !referenced.isFinal()
                    || !isConstantType(referenced.type())) {
                return false;
            }
            if (referenced.ownerClass().equals(owner.name())
                    && declarationIndexes.getOrDefault(key(referenced), Integer.MAX_VALUE)
                    >= declarationIndexes.getOrDefault(key(target), -1)) {
                return false;
            }
            TypeSymbol referencedOwner = types.get(referenced.ownerClass());
            return referencedOwner != null
                    && isCompileTimeConstant(referencedOwner, referenced, active);
        }
        if (expression instanceof FieldAccessExpression access) {
            String qualifier = qualifiedName(access.receiver());
            TypeResolver.Resolution typeResolution = qualifier == null ? null
                    : hierarchy.resolveType(qualifier, owner);
            TypeSymbol referencedOwner = typeResolution == null
                    ? null : typeResolution.type().orElse(null);
            if (referencedOwner == null) {
                return false;
            }
            ClassHierarchy.FieldResolution fieldResolution = hierarchy.resolveField(
                    referencedOwner.selfType(), access.fieldName());
            FieldSymbol referenced = fieldResolution.ambiguous()
                    ? null : fieldResolution.selected().orElse(null);
            TypeSymbol declaringOwner = referenced == null
                    ? null : types.get(referenced.ownerClass());
            return referenced != null && referenced.isStatic() && referenced.isFinal()
                    && isConstantType(referenced.type()) && declaringOwner != null
                    && isCompileTimeConstant(declaringOwner, referenced, active);
        }
        return false;
    }

    private static boolean isStandardStream(TypeSymbol owner, FieldSymbol field) {
        return owner.name().equals("ironwood.lang.System")
                && field.isStatic() && field.isFinal()
                && field.accessModifier() == ironwood.compiler.ast.AccessModifier.PUBLIC
                && ((field.declaration().name().equals("out") || field.declaration().name().equals("err"))
                    && field.type().equals(IrType.reference("ironwood.io.PrintStream"))
                    || field.declaration().name().equals("in")
                    && field.type().equals(IrType.reference("ironwood.io.InputStream")));
    }

    private ConstantValue evaluateField(TypeSymbol owner, FieldSymbol field) {
        String key = key(field);
        State state = states.get(key);
        if (state == State.DONE) {
            return constants.get(key);
        }
        if (state == State.FAILED) {
            return null;
        }
        if (state == State.ACTIVE) {
            diagnostics.add(Diagnostic.error(owner.source(), field.declaration().nameSpan(),
                    "cyclic static constant initializer involving '" + key + "'"));
            states.put(key, State.FAILED);
            return null;
        }
        states.put(key, State.ACTIVE);
        if (field.declaration().initializer().isEmpty()) {
            if (field.isFinal()) {
                diagnostics.add(Diagnostic.error(owner.source(), field.declaration().nameSpan(),
                        "static final field '" + field.declaration().name()
                                + "' requires a compile-time constant initializer"));
                states.put(key, State.FAILED);
                return null;
            }
            ConstantValue value = defaultValue(field.type());
            constants.put(key, value);
            states.put(key, State.DONE);
            return value;
        }

        Expression initializer = field.declaration().initializer().orElseThrow();
        ConstantValue raw = evaluateExpression(owner, field, initializer);
        ConstantValue converted = raw == null ? null
                : assignmentConversion(raw, field.type(), initializer.span());
        if (converted == null && raw != null) {
            diagnostics.add(Diagnostic.error(owner.source(), initializer.span(),
                    "cannot initialize static " + field.type().displayName() + " field '"
                            + field.declaration().name() + "' with " + raw.type().displayName()
                            + " constant"));
        }
        if (converted == null) {
            states.put(key, State.FAILED);
            return null;
        }
        constants.put(key, converted);
        states.put(key, State.DONE);
        return converted;
    }

    private ConstantValue evaluateExpression(TypeSymbol owner, FieldSymbol target,
                                             Expression expression) {
        if (expression instanceof BooleanLiteralExpression literal) {
            return new ConstantValue(IrType.I1, literal.value() ? 1 : 0);
        }
        if (expression instanceof CharacterLiteralExpression literal) {
            return new ConstantValue(IrType.U16, literal.value());
        }
        if (expression instanceof IntegerLiteralExpression literal) {
            return integerLiteral(owner, literal, false);
        }
        if (expression instanceof FloatingLiteralExpression literal) {
            return floatingLiteral(owner, literal);
        }
        if (expression instanceof StringLiteralExpression literal) {
            return ConstantValue.stringValue(stringPool.intern(literal.value(), literal.span()));
        }
        if (expression instanceof NullLiteralExpression) {
            return ConstantValue.nullValue();
        }
        if (expression instanceof NameExpression name) {
            ClassHierarchy.FieldResolution fieldResolution = hierarchy.resolveField(
                    owner.selfType(), name.name());
            if (fieldResolution.ambiguous()) {
                diagnostics.add(Diagnostic.error(owner.source(), name.span(),
                        "ambiguous inherited interface constant '" + name.name()
                                + "'; candidates are " + fieldResolution.candidates().stream()
                                .map(FieldSymbol::ownerClass).reduce((left, right) -> left + ", " + right)
                                .orElse("")));
                return null;
            }
            FieldSymbol referenced = fieldResolution.selected().orElse(null);
            if (referenced == null && fieldResolution.candidates().isEmpty()) {
                List<FieldSymbol> imported = staticImports.fields(owner.unit(), name.name());
                if (imported.size() > 1) {
                    diagnostics.add(Diagnostic.error(owner.source(), name.span(),
                            "ambiguous static-imported field '" + name.name()
                                    + "'; candidates are " + imported.stream()
                                    .map(field -> field.ownerClass() + "."
                                            + field.declaration().name())
                                    .reduce((left, right) -> left + ", " + right).orElse("")));
                    return null;
                }
                referenced = imported.isEmpty() ? null : imported.getFirst();
            }
            if (referenced == null) {
                diagnostics.add(Diagnostic.error(owner.source(), name.span(),
                        "unknown static constant '" + name.name() + "'"));
                return null;
            }
            if (referenced.ownerClass().equals(owner.name())
                    && declarationIndexes.getOrDefault(key(referenced), Integer.MAX_VALUE)
                    >= declarationIndexes.getOrDefault(key(target), -1)) {
                diagnostics.add(Diagnostic.error(owner.source(), name.span(),
                        "static constant '" + name.name()
                                + "' must be declared earlier in type '" + owner.name() + "'"));
                return null;
            }
            return referencedConstant(owner, referenced, name.span());
        }
        if (expression instanceof FieldAccessExpression access) {
            String root = rootName(access.receiver());
            if (root != null && owner.declaredFields().containsKey(root)) {
                diagnostics.add(Diagnostic.error(owner.source(), access.span(),
                        "static field initializer cannot read runtime field '" + root
                                + "' through a member access"));
                return null;
            }
            String qualifier = qualifiedName(access.receiver());
            TypeResolver.Resolution resolution = qualifier == null ? null
                    : hierarchy.resolveType(qualifier, owner);
            TypeSymbol referencedOwner = resolution == null
                    ? null : resolution.type().orElse(null);
            if (referencedOwner == null) {
                diagnostics.add(Diagnostic.error(owner.source(), access.receiver().span(),
                        "static constant qualifier must name an accessible class"));
                return null;
            }
            if (resolution.inaccessible()) {
                diagnostics.add(Diagnostic.error(owner.source(), access.receiver().span(),
                        "type '" + referencedOwner.name() + "' is not accessible from package '"
                                + owner.packageName() + "'"));
            }
            ClassHierarchy.FieldResolution fieldResolution = hierarchy.resolveField(
                    referencedOwner.selfType(), access.fieldName());
            if (fieldResolution.ambiguous()) {
                diagnostics.add(Diagnostic.error(owner.source(), access.fieldNameSpan(),
                        "ambiguous inherited interface constant '" + access.fieldName()
                                + "'; candidates are " + fieldResolution.candidates().stream()
                                .map(FieldSymbol::ownerClass).reduce((left, right) -> left + ", " + right)
                                .orElse("")));
                return null;
            }
            FieldSymbol referenced = fieldResolution.selected().orElse(null);
            if (referenced == null) {
                diagnostics.add(Diagnostic.error(owner.source(), access.fieldNameSpan(),
                        "type '" + referencedOwner.name() + "' has no field '"
                                + access.fieldName() + "'"));
                return null;
            }
            if (!isAccessible(owner, referenced, referencedOwner.name())) {
                diagnostics.add(Diagnostic.error(owner.source(), access.fieldNameSpan(),
                        "field '" + access.fieldName() + "' is not accessible in class '"
                                + referenced.ownerClass() + "'"));
                return null;
            }
            return referencedConstant(owner, referenced, access.fieldNameSpan());
        }
        if (expression instanceof UnaryExpression unary) {
            if (unary.operator() == UnaryOperator.NEGATE
                    && unary.operand() instanceof IntegerLiteralExpression literal) {
                ConstantValue minimum = integerLiteral(owner, literal, true);
                if (minimum != null) {
                    return minimum;
                }
            }
            ConstantValue operand = evaluateExpression(owner, target, unary.operand());
            return operand == null ? null : unary(owner, unary, operand);
        }
        if (expression instanceof BinaryExpression binary) {
            ConstantValue left = evaluateExpression(owner, target, binary.left());
            ConstantValue right = evaluateExpression(owner, target, binary.right());
            return left == null || right == null ? null : binary(owner, binary, left, right);
        }
        if (expression instanceof ConditionalExpression conditional) {
            ConstantValue condition = evaluateExpression(owner, target, conditional.condition());
            ConstantValue whenTrue = evaluateExpression(owner, target, conditional.whenTrue());
            ConstantValue whenFalse = evaluateExpression(owner, target, conditional.whenFalse());
            if (condition == null || whenTrue == null || whenFalse == null) {
                return null;
            }
            if (!condition.type().equals(IrType.I1)) {
                diagnostics.add(Diagnostic.error(owner.source(), conditional.condition().span(),
                        "static constant conditional requires a boolean condition"));
                return null;
            }
            IrType resultType = conditionalType(whenTrue.type(), whenFalse.type());
            if (resultType == null) {
                diagnostics.add(Diagnostic.error(owner.source(), conditional.questionSpan(),
                        "static constant conditional has incompatible branch types"));
                return null;
            }
            ConstantValue chosen = condition.value().intValue() == 0 ? whenFalse : whenTrue;
            return assignmentConversion(chosen, resultType, conditional.span());
        }
        if (expression instanceof CastExpression cast) {
            ConstantValue operand = evaluateExpression(owner, target, cast.operand());
            if (operand == null) {
                return null;
            }
            IrType castType = resolveCastType(owner, cast.targetType());
            if (castType == null) {
                diagnostics.add(Diagnostic.error(owner.source(), cast.targetType().span(),
                        "static constant cast target is not supported"));
                return null;
            }
            ConstantValue converted = explicitConversion(operand, castType);
            if (converted == null) {
                diagnostics.add(Diagnostic.error(owner.source(), cast.span(),
                        "cannot cast " + operand.type().displayName() + " constant to "
                                + castType.displayName()));
            }
            return converted;
        }
        diagnostics.add(Diagnostic.error(owner.source(), expression.span(),
                "static field initializer must be a compile-time constant; new, calls, "
                        + "array creation, assignments, and runtime field loads are not supported"));
        return null;
    }

    private ConstantValue referencedConstant(TypeSymbol context, FieldSymbol field, SourceSpan span) {
        if (!field.isStatic()) {
            diagnostics.add(Diagnostic.error(context.source(), span,
                    "instance field '" + field.declaration().name()
                            + "' is not a compile-time constant"));
            return null;
        }
        if (!field.isFinal() || !isConstantType(field.type())) {
            diagnostics.add(Diagnostic.error(context.source(), span,
                    "static field '" + field.declaration().name()
                            + "' is not a static-final primitive or String constant"));
            return null;
        }
        TypeSymbol owner = types.get(field.ownerClass());
        return owner == null ? null : evaluateField(owner, field);
    }

    private ConstantValue integerLiteral(TypeSymbol owner, IntegerLiteralExpression literal,
                                         boolean negated) {
        IntegerLiteralDecoder.Result result = IntegerLiteralDecoder.decode(literal.text(), negated);
        if (!result.successful()) {
            diagnostics.add(Diagnostic.error(owner.source(), literal.span(), result.error()));
            return null;
        }
        return integral(result.type(), result.value());
    }

    private ConstantValue floatingLiteral(TypeSymbol owner, FloatingLiteralExpression literal) {
        String text = literal.text().replace("_", "");
        char suffix = text.charAt(text.length() - 1);
        boolean single = suffix == 'f' || suffix == 'F';
        if (single || suffix == 'd' || suffix == 'D') {
            text = text.substring(0, text.length() - 1);
        }
        try {
            return single ? new ConstantValue(IrType.F32, Float.parseFloat(text))
                    : new ConstantValue(IrType.F64, Double.parseDouble(text));
        } catch (NumberFormatException exception) {
            diagnostics.add(Diagnostic.error(owner.source(), literal.span(),
                    "malformed floating-point literal '" + literal.text() + "'"));
            return null;
        }
    }

    private ConstantValue unary(TypeSymbol owner, UnaryExpression expression,
                                ConstantValue operand) {
        if (expression.operator() == UnaryOperator.NOT) {
            if (!operand.type().equals(IrType.I1)) {
                return invalidUnary(owner, expression, operand);
            }
            return bool(operand.value().intValue() == 0);
        }
        if (!operand.type().isNumeric()) {
            return invalidUnary(owner, expression, operand);
        }
        IrType promoted = PrimitiveConversions.unaryPromotion(operand.type());
        ConstantValue value = explicitConversion(operand, promoted);
        return switch (expression.operator()) {
            case POSITIVE -> value;
            case NEGATE -> promoted.isFloating()
                    ? floating(promoted, -value.value().doubleValue())
                    : integral(promoted, BigInteger.valueOf(value.value().longValue()).negate());
            case BITWISE_COMPLEMENT -> promoted.isIntegral()
                    ? integral(promoted, BigInteger.valueOf(value.value().longValue()).not())
                    : invalidUnary(owner, expression, operand);
            case NOT -> throw new IllegalStateException();
        };
    }

    private ConstantValue invalidUnary(TypeSymbol owner, UnaryExpression expression,
                                       ConstantValue operand) {
        diagnostics.add(Diagnostic.error(owner.source(), expression.operatorSpan(),
                "operator '" + unaryText(expression.operator()) + "' is not defined for "
                        + operand.type().displayName() + " constants"));
        return null;
    }

    private ConstantValue binary(TypeSymbol owner, BinaryExpression expression,
                                 ConstantValue left, ConstantValue right) {
        BinaryOperator operator = expression.operator();
        if (operator == BinaryOperator.ADD
                && (left.type().equals(STRING_TYPE) || right.type().equals(STRING_TYPE))) {
            String leftText = constantString(left);
            String rightText = constantString(right);
            if (leftText == null || rightText == null) {
                return invalidBinary(owner, expression, left, right);
            }
            return ConstantValue.stringValue(stringPool.intern(
                    leftText + rightText, expression.span()));
        }
        if (operator == BinaryOperator.LOGICAL_AND || operator == BinaryOperator.LOGICAL_OR) {
            if (!left.type().equals(IrType.I1) || !right.type().equals(IrType.I1)) {
                return invalidBinary(owner, expression, left, right);
            }
            boolean l = left.value().intValue() != 0;
            boolean r = right.value().intValue() != 0;
            return bool(operator == BinaryOperator.LOGICAL_AND ? l && r : l || r);
        }
        if ((operator == BinaryOperator.BITWISE_AND || operator == BinaryOperator.BITWISE_XOR
                || operator == BinaryOperator.BITWISE_OR)
                && left.type().equals(IrType.I1) && right.type().equals(IrType.I1)) {
            int l = left.value().intValue();
            int r = right.value().intValue();
            return bool(switch (operator) {
                case BITWISE_AND -> (l & r) != 0;
                case BITWISE_XOR -> (l ^ r) != 0;
                case BITWISE_OR -> (l | r) != 0;
                default -> throw new IllegalStateException();
            });
        }
        if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL) {
            boolean equal;
            if (left.type().isNumeric() && right.type().isNumeric()) {
                IrType promoted = PrimitiveConversions.binaryPromotion(left.type(), right.type());
                ConstantValue l = explicitConversion(left, promoted);
                ConstantValue r = explicitConversion(right, promoted);
                equal = promoted.isFloating()
                        ? l.value().doubleValue() == r.value().doubleValue()
                        : l.value().longValue() == r.value().longValue();
            } else if (left.type().equals(IrType.I1) && right.type().equals(IrType.I1)) {
                equal = left.value().intValue() == right.value().intValue();
            } else if (left.isNull() && right.isNull()) {
                equal = true;
            } else {
                return invalidBinary(owner, expression, left, right);
            }
            return bool(operator == BinaryOperator.EQUAL ? equal : !equal);
        }
        if (isShift(operator)) {
            if (!left.type().isIntegral() || !right.type().isIntegral()) {
                return invalidBinary(owner, expression, left, right);
            }
            IrType promoted = PrimitiveConversions.unaryPromotion(left.type());
            long l = explicitConversion(left, promoted).value().longValue();
            int distance = explicitConversion(right,
                    PrimitiveConversions.unaryPromotion(right.type())).value().intValue()
                    & (promoted.equals(IrType.I64) ? 63 : 31);
            if (promoted.equals(IrType.I64)) {
                return integral(promoted, BigInteger.valueOf(switch (operator) {
                    case SHIFT_LEFT -> l << distance;
                    case SHIFT_RIGHT -> l >> distance;
                    case UNSIGNED_SHIFT_RIGHT -> l >>> distance;
                    default -> throw new IllegalStateException();
                }));
            }
            int value = (int) l;
            return integral(promoted, BigInteger.valueOf(switch (operator) {
                case SHIFT_LEFT -> value << distance;
                case SHIFT_RIGHT -> value >> distance;
                case UNSIGNED_SHIFT_RIGHT -> value >>> distance;
                default -> throw new IllegalStateException();
            }));
        }
        if (!left.type().isNumeric() || !right.type().isNumeric()) {
            return invalidBinary(owner, expression, left, right);
        }
        IrType promoted = PrimitiveConversions.binaryPromotion(left.type(), right.type());
        ConstantValue l = explicitConversion(left, promoted);
        ConstantValue r = explicitConversion(right, promoted);
        if (operator == BinaryOperator.LESS || operator == BinaryOperator.LESS_EQUAL
                || operator == BinaryOperator.GREATER || operator == BinaryOperator.GREATER_EQUAL) {
            boolean comparison = promoted.isFloating()
                    ? compareFloating(operator, l.value().doubleValue(), r.value().doubleValue())
                    : compareIntegral(operator, l.value().longValue(), r.value().longValue());
            return bool(comparison);
        }
        if (promoted.isFloating()) {
            double a = l.value().doubleValue();
            double b = r.value().doubleValue();
            if (operator != BinaryOperator.ADD && operator != BinaryOperator.SUBTRACT
                    && operator != BinaryOperator.MULTIPLY && operator != BinaryOperator.DIVIDE
                    && operator != BinaryOperator.REMAINDER) {
                return invalidBinary(owner, expression, left, right);
            }
            if (promoted.equals(IrType.F32)) {
                float first = l.value().floatValue();
                float second = r.value().floatValue();
                return new ConstantValue(IrType.F32, switch (operator) {
                    case ADD -> first + second;
                    case SUBTRACT -> first - second;
                    case MULTIPLY -> first * second;
                    case DIVIDE -> first / second;
                    case REMAINDER -> first % second;
                    default -> throw new IllegalStateException();
                });
            }
            double value = switch (operator) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                case DIVIDE -> a / b;
                case REMAINDER -> a % b;
                default -> throw new IllegalStateException();
            };
            return floating(promoted, value);
        }
        long a = l.value().longValue();
        long b = r.value().longValue();
        if ((operator == BinaryOperator.DIVIDE || operator == BinaryOperator.REMAINDER) && b == 0) {
            diagnostics.add(Diagnostic.error(owner.source(), expression.operatorSpan(),
                    "integer division by zero in static constant initializer"));
            return null;
        }
        long value = promoted.equals(IrType.I64) && a == Long.MIN_VALUE && b == -1
                && operator == BinaryOperator.DIVIDE ? Long.MIN_VALUE
                : promoted.equals(IrType.I64) && a == Long.MIN_VALUE && b == -1
                && operator == BinaryOperator.REMAINDER ? 0
                : switch (operator) {
            case ADD -> a + b;
            case SUBTRACT -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> a / b;
            case REMAINDER -> a % b;
            case BITWISE_AND -> a & b;
            case BITWISE_XOR -> a ^ b;
            case BITWISE_OR -> a | b;
            default -> Long.MIN_VALUE;
        };
        if (operator != BinaryOperator.ADD && operator != BinaryOperator.SUBTRACT
                && operator != BinaryOperator.MULTIPLY && operator != BinaryOperator.DIVIDE
                && operator != BinaryOperator.REMAINDER && operator != BinaryOperator.BITWISE_AND
                && operator != BinaryOperator.BITWISE_XOR && operator != BinaryOperator.BITWISE_OR) {
            return invalidBinary(owner, expression, left, right);
        }
        return integral(promoted, BigInteger.valueOf(value));
    }

    private ConstantValue invalidBinary(TypeSymbol owner, BinaryExpression expression,
                                        ConstantValue left, ConstantValue right) {
        diagnostics.add(Diagnostic.error(owner.source(), expression.operatorSpan(),
                "operator is not defined for static constants of type "
                        + left.type().displayName() + " and " + right.type().displayName()));
        return null;
    }

    private ConstantValue assignmentConversion(ConstantValue value, IrType target, SourceSpan span) {
        if (target.equals(value.type())) {
            return value;
        }
        if (target.isReference() && value.isNull()) {
            return ConstantValue.nullValue();
        }
        if (!target.isNumeric() || !value.type().isNumeric()) {
            return null;
        }
        if (PrimitiveConversions.canWiden(target, value.type())) {
            return explicitConversion(value, target);
        }
        if (value.type().equals(IrType.I32) && (target.equals(IrType.I8)
                || target.equals(IrType.I16) || target.equals(IrType.U16))) {
            long raw = value.value().longValue();
            if (target.equals(IrType.I8) && raw >= Byte.MIN_VALUE && raw <= Byte.MAX_VALUE
                    || target.equals(IrType.I16) && raw >= Short.MIN_VALUE && raw <= Short.MAX_VALUE
                    || target.equals(IrType.U16) && raw >= Character.MIN_VALUE
                    && raw <= Character.MAX_VALUE) {
                return explicitConversion(value, target);
            }
        }
        return null;
    }

    private ConstantValue explicitConversion(ConstantValue value, IrType target) {
        if (target.equals(value.type())) {
            return value;
        }
        if (target.isReference() && value.isNull()) {
            return ConstantValue.nullValue();
        }
        if (!target.isNumeric() || !value.type().isNumeric()) {
            return null;
        }
        if (target.isFloating()) {
            if (target.equals(IrType.F32)) {
                float numeric = value.type().isIntegral()
                        ? (float) value.value().longValue() : value.value().floatValue();
                return new ConstantValue(IrType.F32, numeric);
            }
            return new ConstantValue(IrType.F64, value.value().doubleValue());
        }
        if (value.type().isFloating()) {
            double numeric = value.value().doubleValue();
            long integral;
            if (Double.isNaN(numeric)) {
                integral = 0;
            } else if (target.equals(IrType.I64)) {
                integral = numeric <= Long.MIN_VALUE ? Long.MIN_VALUE
                        : numeric >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) numeric;
            } else {
                int intermediate = numeric <= Integer.MIN_VALUE ? Integer.MIN_VALUE
                        : numeric >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) numeric;
                integral = intermediate;
            }
            return integral(target, BigInteger.valueOf(integral));
        }
        return integral(target, BigInteger.valueOf(value.value().longValue()));
    }

    private IrType resolveCastType(TypeSymbol owner, TypeName type) {
        if (type.kind() != TypeName.Kind.REFERENCE && type.kind() != TypeName.Kind.ARRAY) {
            return SemanticAnalyzer.irType(type);
        }
        if (type.kind() == TypeName.Kind.ARRAY) {
            IrType element = resolveCastType(owner, type.elementType());
            return element == null ? null : IrType.array(element);
        }
        TypeResolver.Resolution resolution = hierarchy.resolveType(type.referenceName(), owner);
        return resolution.type().map(symbol -> IrType.reference(symbol.name())).orElse(null);
    }

    private IrType conditionalType(IrType left, IrType right) {
        if (left.equals(right)) {
            return left;
        }
        if (left.isNumeric() && right.isNumeric()) {
            return PrimitiveConversions.binaryPromotion(left, right);
        }
        if (left.equals(IrType.NULL) && right.isReference()) {
            return right;
        }
        if (right.equals(IrType.NULL) && left.isReference()) {
            return left;
        }
        return null;
    }

    private static ConstantValue defaultValue(IrType type) {
        if (type.isReference()) {
            return ConstantValue.nullValue();
        }
        if (type.equals(IrType.F32)) {
            return new ConstantValue(type, 0.0f);
        }
        if (type.equals(IrType.F64)) {
            return new ConstantValue(type, 0.0d);
        }
        if (type.equals(IrType.I64)) {
            return new ConstantValue(type, 0L);
        }
        return new ConstantValue(type, 0);
    }

    private static ConstantValue integral(IrType type, BigInteger value) {
        BigInteger wrapped = wrap(value, type);
        Number numeric;
        if (type.equals(IrType.I64)) {
            numeric = Long.valueOf(wrapped.longValue());
        } else {
            numeric = Integer.valueOf(wrapped.intValue());
        }
        return new ConstantValue(type, numeric);
    }

    private static BigInteger wrap(BigInteger value, IrType type) {
        int bits = switch (type.kind()) {
            case I8 -> 8;
            case I16, U16 -> 16;
            case I32 -> 32;
            case I64 -> 64;
            default -> throw new IllegalArgumentException("not integral");
        };
        BigInteger modulus = BigInteger.ONE.shiftLeft(bits);
        BigInteger wrapped = value.mod(modulus);
        if (!type.equals(IrType.U16) && wrapped.testBit(bits - 1)) {
            wrapped = wrapped.subtract(modulus);
        }
        return wrapped;
    }

    private static ConstantValue floating(IrType type, double value) {
        return new ConstantValue(type, type.equals(IrType.F32)
                ? Float.valueOf((float) value) : Double.valueOf(value));
    }

    private static ConstantValue bool(boolean value) {
        return new ConstantValue(IrType.I1, value ? 1 : 0);
    }

    private boolean isAccessible(TypeSymbol context, FieldSymbol field, String qualifierType) {
        TypeSymbol owner = types.get(field.ownerClass());
        if (owner == null) {
            return false;
        }
        return switch (field.accessModifier()) {
            case PUBLIC -> true;
            case PRIVATE -> owner.sameNest(context);
            case PACKAGE_PRIVATE -> owner.packageName().equals(context.packageName());
            case PROTECTED -> owner.packageName().equals(context.packageName())
                    || hierarchy.isSubtype(context.name(), owner.name())
                    && hierarchy.isSubtype(qualifierType, context.name());
        };
    }

    private static boolean isPrimitive(IrType type) {
        return type.isNumeric() || type.equals(IrType.I1);
    }

    private static boolean isConstantType(IrType type) {
        return isPrimitive(type) || type.equals(STRING_TYPE);
    }

    private static String constantString(ConstantValue value) {
        if (value.type().equals(STRING_TYPE)) {
            return value.stringValue() == null ? null : value.stringValue().value();
        }
        if (value.type().equals(IrType.I1)) {
            return value.value().intValue() == 0 ? "false" : "true";
        }
        if (value.type().equals(IrType.U16)) {
            return Character.toString((char) value.value().intValue());
        }
        if (value.type().equals(IrType.F32)) {
            return Float.toString(value.value().floatValue());
        }
        if (value.type().equals(IrType.F64)) {
            return Double.toString(value.value().doubleValue());
        }
        if (value.type().equals(IrType.I64)) {
            return Long.toString(value.value().longValue());
        }
        if (value.type().isIntegral()) {
            return Integer.toString(value.value().intValue());
        }
        return null;
    }

    private static boolean isShift(BinaryOperator operator) {
        return operator == BinaryOperator.SHIFT_LEFT || operator == BinaryOperator.SHIFT_RIGHT
                || operator == BinaryOperator.UNSIGNED_SHIFT_RIGHT;
    }

    private static boolean compareFloating(BinaryOperator operator, double left, double right) {
        return switch (operator) {
            case LESS -> left < right;
            case LESS_EQUAL -> left <= right;
            case GREATER -> left > right;
            case GREATER_EQUAL -> left >= right;
            default -> throw new IllegalArgumentException();
        };
    }

    private static boolean compareIntegral(BinaryOperator operator, long left, long right) {
        return switch (operator) {
            case LESS -> left < right;
            case LESS_EQUAL -> left <= right;
            case GREATER -> left > right;
            case GREATER_EQUAL -> left >= right;
            default -> throw new IllegalArgumentException();
        };
    }

    private static String unaryText(UnaryOperator operator) {
        return switch (operator) {
            case POSITIVE -> "+";
            case NEGATE -> "-";
            case NOT -> "!";
            case BITWISE_COMPLEMENT -> "~";
        };
    }

    private static String qualifiedName(Expression expression) {
        if (expression instanceof NameExpression name) {
            return name.name();
        }
        if (expression instanceof FieldAccessExpression access) {
            String receiver = qualifiedName(access.receiver());
            return receiver == null ? null : receiver + "." + access.fieldName();
        }
        return null;
    }

    private static String rootName(Expression expression) {
        if (expression instanceof NameExpression name) {
            return name.name();
        }
        if (expression instanceof FieldAccessExpression access) {
            return rootName(access.receiver());
        }
        return null;
    }

    private static String key(FieldSymbol field) {
        return field.ownerClass() + "." + field.declaration().name();
    }

    private enum State {
        ACTIVE,
        DONE,
        FAILED
    }
}
