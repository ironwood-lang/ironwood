// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.*;
import ironwood.compiler.ir.IrType;
import java.util.List;
import java.util.Map;

/** Proves a set's private map-copy constructor and fresh owned iterator.
 * Actual key callbacks remain a separate call-site obligation. This proof is
 * used for a fresh factory result, not to suppress constructor publications.
 */
final class SetCopyFactoryAnalysis {
    private static final Map<String, String> BACKENDS = Map.of(
            "ironwood.ds.HashSet", "ironwood.ds.HashMap",
            "ironwood.ds.IdentityHashSet", "ironwood.ds.IdentityHashMap",
            "ironwood.ds.LinkedHashSet", "ironwood.ds.LinkedHashMap");
    private final Map<String, TypeSymbol> types;
    private final TypeResolver resolver;
    private final EscapeSummaryAnalyzer escapes;
    private final OwnedArrayFieldAnalyzer owned;

    SetCopyFactoryAnalysis(Map<String, TypeSymbol> types, TypeResolver resolver,
                           EscapeSummaryAnalyzer escapes, OwnedArrayFieldAnalyzer owned) {
        this.types = types;
        this.resolver = resolver;
        this.escapes = escapes;
        this.owned = owned;
    }

    static boolean isSet(IrType type) {
        return type != null && type.isNominalReference() && BACKENDS.containsKey(type.referenceName());
    }

    FreshBorrowingFactoryAnalysis.Input prove(CallableSymbol constructor) {
        TypeSymbol owner = types.get(constructor.ownerType());
        if (owner == null || !BACKENDS.containsKey(owner.name()) || !constructor.isConstructor()
                || constructor.accessModifier() != AccessModifier.PRIVATE || constructor.body().isEmpty()
                || constructor.parameters().size() != 1 || constructor.thisInvocation().isPresent()
                || constructor.superInvocation().isPresent() || owner.enclosingInstanceField().isPresent()
                || owner.superclass().filter(parent -> parent.name().equals("ironwood.lang.Object")).isEmpty()
                || !constructor.parameterTypes().getFirst().equals(owner.selfType())
                || escapes.summary(constructor).thisEscapes()) return null;
        List<FieldSymbol> fields = owner.declaredFields().values().stream().filter(field -> !field.isStatic()).toList();
        FieldSymbol map = owner.declaredFields().get("map");
        FieldSymbol iterator = owner.declaredFields().get("reusableIterator");
        if (fields.size() != 2 || map == null || iterator == null || !map.isFinal()
                || map.declaration().initializer().isPresent() || iterator.declaration().initializer().isPresent()
                || map.accessModifier() != AccessModifier.PRIVATE || iterator.accessModifier() != AccessModifier.PRIVATE
                || !owned.isOwned(map) || !owned.isOwned(iterator) || !map.type().isNominalReference()
                || !map.type().referenceName().equals(BACKENDS.get(owner.name()))) return null;
        List<Statement> body = constructor.body().orElseThrow().statements();
        if (body.size() != 2) return null;
        AssignmentExpression membership = assignment(body.getFirst(), "map");
        AssignmentExpression cursor = assignment(body.get(1), "reusableIterator");
        if (membership == null || cursor == null || !(membership.value() instanceof CallExpression copy)
                || !copy.arguments().isEmpty() || !(copy.receiver().orElse(null) instanceof FieldAccessExpression source)
                || !source.fieldName().equals("map") || !(source.receiver() instanceof NameExpression parameter)
                || !parameter.name().equals(constructor.parameters().getFirst().name())
                || !(cursor.value() instanceof NewExpression creation) || creation.arguments().size() != 1
                || !(creation.arguments().getFirst() instanceof ThisExpression)
                || creation.enclosingInstance().isPresent() || creation.anonymousClassBody().isPresent()) return null;
        List<CallableSymbol> copies = escapes.boundTargets(constructor, copy);
        if (copies.size() != 1 || !copies.getFirst().ownerType().equals(map.type().referenceName())
                || copies.getFirst().body().isEmpty()
                || new MapCopyFactoryAnalysis(types, resolver, escapes, owned)
                    .prove(copies.getFirst(), copies.getFirst().body().orElseThrow().statements()) == null) return null;
        TypeSymbol helper = resolver.resolve(creation.className(), owner, creation.span()).type().orElse(null);
        if (helper == null || !helper.name().equals(iterator.type().referenceName())
                || helper.enclosingInstanceField().isPresent()) return null;
        List<CallableSymbol> constructors = escapes.boundTargets(constructor.linkageName(), creation.span(), helper.simpleName());
        if (constructors.size() != 1 || !escapes.constructorArgumentIsConfined(constructors.getFirst(), 0)
                || escapes.summary(constructors.getFirst()).thisEscapes()) return null;
        return new FreshBorrowingFactoryAnalysis.Input(ReturnOrigin.parameter(0), List.of(), owner.selfType(), true,
                !owner.name().equals("ironwood.ds.IdentityHashSet"));
    }

    private static AssignmentExpression assignment(Statement statement, String fieldName) {
        if (!(statement instanceof ExpressionStatement action) || !(action.expression() instanceof AssignmentExpression assignment)
                || assignment.operator() != AssignmentOperator.ASSIGN
                || !(assignment.target() instanceof FieldAccessExpression field)
                || !(field.receiver() instanceof ThisExpression) || !field.fieldName().equals(fieldName)) return null;
        return assignment;
    }
}
