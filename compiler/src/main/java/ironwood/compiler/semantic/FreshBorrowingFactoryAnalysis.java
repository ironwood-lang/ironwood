// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.*;
import ironwood.compiler.ir.IrType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Proves simple fresh wrapper factories from their bodies and constructor effects.
 * No API names receive a borrowing exemption. More complex bodies remain subject
 * to the ordinary conservative escape summary.
 */
final class FreshBorrowingFactoryAnalysis {
    record Input(ReturnOrigin origin, List<FieldSymbol> fields, IrType type) {
        Input { fields = List.copyOf(fields); }
    }
    record Result(IrType type, Map<FieldSymbol, Input> borrows, List<Input> elements) {
        Result { borrows = Map.copyOf(borrows); elements = List.copyOf(elements); }
        Result(IrType type, Map<FieldSymbol, Input> borrows) { this(type, borrows, List.of()); }
    }

    private final Map<String, TypeSymbol> types;
    private final TypeResolver resolver;
    private final EscapeSummaryAnalyzer escapes;
    private final OwnedArrayFieldAnalyzer owned;
    private final FieldSymbol checkingField;

    FreshBorrowingFactoryAnalysis(Map<String, TypeSymbol> types, TypeResolver resolver,
                                 EscapeSummaryAnalyzer escapes, OwnedArrayFieldAnalyzer owned,
                                 FieldSymbol checkingField) {
        this.types = types;
        this.resolver = resolver;
        this.escapes = escapes;
        this.owned = owned;
        this.checkingField = checkingField;
    }

    Result prove(CallableSymbol method) { return prove(method, new LinkedHashSet<>()); }

    private Result prove(CallableSymbol method, Set<String> visiting) {
        if (owned == null || !visiting.add(method.linkageName()) || method.body().isEmpty()
                || !escapes.summary(method).returnsOwnedFresh()) return null;
        try {
            List<Statement> statements = method.body().orElseThrow().statements();
            Result list = listFactory(method, statements);
            if (list != null) return list;
            if (statements.size() != 1 || !(statements.getFirst() instanceof ReturnStatement returned)
                    || returned.value().isEmpty()) return null;
            Expression expression = returned.value().orElseThrow();
            if (expression instanceof NewExpression creation) return allocation(method, creation);
            if (!(expression instanceof CallExpression call)) return null;
            List<CallableSymbol> targets = escapes.boundTargets(method.linkageName(), call.span(), call.methodName());
            if (targets == null || targets.size() != 1) return null;
            CallableSymbol target = targets.getFirst();
            if (target == null || target.isStatic() && call.receiver().isPresent()
                    && !(call.receiver().orElseThrow() instanceof NameExpression)) return null;
            Result nested = prove(target, visiting);
            if (nested == null) return null;
            Input receiver = target.isStatic() ? null : call.receiver().isPresent()
                    ? input(method, call.receiver().orElseThrow())
                    : new Input(ReturnOrigin.thisOrigin(), List.of(), types.get(method.ownerType()).selfType());
            if (!target.isStatic() && receiver == null) return null;
            List<Input> arguments = new ArrayList<>();
            for (Expression argument : call.arguments()) {
                Input value = input(method, argument);
                if (value == null && !isLiteral(argument)) return null;
                arguments.add(value);
            }
            Map<FieldSymbol, Input> mapped = new LinkedHashMap<>();
            for (var entry : nested.borrows().entrySet()) {
                Input value = remap(entry.getValue(), receiver, arguments);
                if (value == null) return null;
                mapped.put(entry.getKey(), value);
            }
            List<Input> elements = new ArrayList<>();
            for (Input element : nested.elements()) {
                Input value = remap(element, receiver, arguments);
                if (value == null) return null;
                elements.add(value);
            }
            return new Result(nested.type(), mapped, elements);
        } finally {
            visiting.remove(method.linkageName());
        }
    }

    private static Input remap(Input input, Input receiver, List<Input> arguments) {
        Input source = switch (input.origin().kind()) {
            case THIS -> receiver;
            case PARAMETER -> input.origin().parameterIndex() < arguments.size()
                    ? arguments.get(input.origin().parameterIndex()) : null;
            case ELEMENT_OF_PARAMETER -> null;
        };
        if (source == null) return null;
        List<FieldSymbol> fields = new ArrayList<>(source.fields());
        fields.addAll(input.fields());
        return new Input(source.origin(), fields, input.type());
    }

    /** A bounded list of borrowed inputs with explicit failure cleanup. Element
     * insertion uses the existing audited ArrayList loan contract, never an
     * arbitrary add method selected by its spelling.
     */
    private Result listFactory(CallableSymbol method, List<Statement> statements) {
        if (statements.size() != 2 || !(statements.getFirst() instanceof LocalVariableDeclaration local)
                || !(local.initializer() instanceof NewExpression creation)
                || !(statements.get(1) instanceof TryStatement guarded)
                || guarded.finallyBlock().isPresent() || !method.returnType().isNominalReference()
                || !method.returnType().referenceName().equals("ironwood.ds.ArrayList")
                || creation.enclosingInstance().isPresent() || creation.anonymousClassBody().isPresent()
                || creation.arguments().stream().anyMatch(argument -> !isLiteral(argument))) return null;
        TypeSymbol created = resolver.resolve(creation.className(), types.get(method.ownerType()),
                creation.span()).type().orElse(null);
        if (created == null || !created.name().equals("ironwood.ds.ArrayList")) return null;
        List<CallableSymbol> constructors = escapes.boundTargets(method.linkageName(), creation.span(), created.simpleName());
        if (constructors.size() != 1 || escapes.summary(constructors.getFirst()).thisEscapes()) return null;
        Set<String> caught = new LinkedHashSet<>();
        for (CatchClause clause : guarded.catches()) {
            for (var name : clause.types()) {
                TypeSymbol type = resolver.resolve(name.referenceName(), types.get(method.ownerType()), name.span()).type().orElse(null);
                if (type == null) return null;
                caught.add(type.name());
            }
            List<Statement> cleanup = clause.body().statements();
            if (cleanup.size() != 2 || !(cleanup.getFirst() instanceof FreeStatement free)
                    || !(free.value() instanceof NameExpression value) || !value.name().equals(local.name())
                    || !(cleanup.get(1) instanceof ThrowStatement thrown)
                    || !(thrown.value() instanceof NameExpression failure)
                    || !failure.name().equals(clause.variableName())) return null;
        }
        if (!caught.equals(Set.of("ironwood.lang.RuntimeException", "ironwood.lang.Error"))) return null;
        List<Statement> body = guarded.body().statements();
        if (body.size() < 2 || !(body.getLast() instanceof ReturnStatement returned)
                || !(returned.value().orElse(null) instanceof NameExpression value)
                || !value.name().equals(local.name())) return null;
        List<Input> elements = new ArrayList<>();
        for (Statement statement : body.subList(0, body.size() - 1)) {
            if (!(statement instanceof ExpressionStatement action) || !(action.expression() instanceof CallExpression call)
                    || !(call.receiver().orElse(null) instanceof NameExpression receiver)
                    || !receiver.name().equals(local.name()) || call.arguments().size() != 1) return null;
            List<CallableSymbol> targets = escapes.boundTargets(method.linkageName(), call.span(), call.methodName());
            if (targets.size() != 1 || !targets.getFirst().ownerType().equals("ironwood.ds.ArrayList")
                    || !targets.getFirst().sourceName().equals("add")
                    || !DataStructureSemantics.retainedArguments(targets.getFirst()).equals(Set.of(0))) return null;
            Input element = input(method, call.arguments().getFirst());
            if (element == null || !element.type().isReference()) return null;
            elements.add(element);
        }
        return new Result(method.returnType(), Map.of(), elements);
    }

    private Result allocation(CallableSymbol method, NewExpression creation) {
        if (creation.enclosingInstance().isPresent() || creation.anonymousClassBody().isPresent()) return null;
        TypeSymbol type = resolver.resolve(creation.className(), types.get(method.ownerType()),
                creation.span()).type().orElse(null);
        if (type == null || type.enclosingInstanceField().isPresent()) return null;
        List<CallableSymbol> targets = escapes.boundTargets(method.linkageName(), creation.span(), type.simpleName());
        if (targets == null || targets.size() != 1) return null;
        CallableSymbol constructor = targets.getFirst();
        if (constructor == null || !constructor.ownerType().equals(type.name())) return null;
        for (TypeSymbol current = type; current != null; current = current.superclass().orElse(null)) {
            if (current.constructors().stream().anyMatch(candidate -> escapes.summary(candidate).thisEscapes()))
                return null;
        }
        Map<FieldSymbol, Input> borrows = new LinkedHashMap<>();
        for (int index = 0; index < creation.arguments().size(); index++) {
            Expression argument = creation.arguments().get(index);
            Input value = input(method, argument);
            if (value == null && !isLiteral(argument)) return null;
            if (value == null || !value.type().isReference()) continue;
            if (!escapes.constructorArgumentIsConfined(constructor, index)) return null;
            if (!escapes.summary(constructor).parameterEscapes(index)) continue;
            FieldSymbol field = escapes.retainedParameterField(constructor, index);
            if (field == null || !field.isFinal() || owned.isOwned(field) || !owned.isEncapsulated(field)) return null;
            borrows.put(field, value);
        }
        return borrows.isEmpty() ? null : new Result(type.selfType(), borrows);
    }

    private static boolean isLiteral(Expression expression) {
        return expression instanceof IntegerLiteralExpression || expression instanceof FloatingLiteralExpression
                || expression instanceof BooleanLiteralExpression || expression instanceof CharacterLiteralExpression
                || expression instanceof StringLiteralExpression || expression instanceof NullLiteralExpression;
    }

    private Input input(CallableSymbol method, Expression expression) {
        if (expression instanceof ThisExpression && !method.isStatic())
            return new Input(ReturnOrigin.thisOrigin(), List.of(), types.get(method.ownerType()).selfType());
        if (expression instanceof NameExpression name) {
            for (int index = 0; index < method.parameters().size(); index++) {
                if (method.parameters().get(index).name().equals(name.name()))
                    return new Input(ReturnOrigin.parameter(index), List.of(), method.parameterTypes().get(index));
            }
            return null;
        }
        if (!(expression instanceof FieldAccessExpression access)) return null;
        Input receiver = input(method, access.receiver());
        if (receiver == null || !receiver.type().isNominalReference()) return null;
        TypeSymbol type = types.get(receiver.type().referenceName());
        FieldSymbol field = type == null ? null : type.declaredFields().get(access.fieldName());
        if (field == null || field.isStatic() || !field.isFinal()
                || field.accessModifier() != AccessModifier.PRIVATE
                || !field.equals(checkingField) && !owned.isEncapsulated(field)) return null;
        List<FieldSymbol> fields = new ArrayList<>(receiver.fields());
        fields.add(field);
        return new Input(receiver.origin(), fields, field.type());
    }
}
