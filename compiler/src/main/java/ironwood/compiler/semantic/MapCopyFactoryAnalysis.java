// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.*;
import ironwood.compiler.ir.IrType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Proves independent storage for direct bundled-map membership copies.
 * The traversal cannot mutate or publish source entries. Destination insertion
 * uses the existing audited container contract; callers must separately prove
 * the actual value-key callbacks non-retaining before applying item loans.
 */
final class MapCopyFactoryAnalysis {
    private static final Set<String> MAPS = Set.of("ironwood.ds.HashMap",
            "ironwood.ds.IdentityHashMap", "ironwood.ds.LinkedHashMap");
    private final Map<String, TypeSymbol> types;
    private final TypeResolver resolver;
    private final EscapeSummaryAnalyzer escapes;
    private final OwnedArrayFieldAnalyzer owned;

    MapCopyFactoryAnalysis(Map<String, TypeSymbol> types, TypeResolver resolver,
                           EscapeSummaryAnalyzer escapes, OwnedArrayFieldAnalyzer owned) {
        this.types = types;
        this.resolver = resolver;
        this.escapes = escapes;
        this.owned = owned;
    }

    static boolean isMap(IrType type) {
        return type != null && type.isNominalReference() && MAPS.contains(type.referenceName());
    }

    FreshBorrowingFactoryAnalysis.Result prove(CallableSymbol method, List<Statement> statements) {
        TypeSymbol source = types.get(method.ownerType());
        if (method.isStatic() || !method.parameterTypes().isEmpty() || !isMap(method.returnType())
                || !method.returnType().referenceName().equals(method.ownerType()) || source == null
                || statements.size() != 2 || !(statements.getFirst() instanceof LocalVariableDeclaration result)
                || !(result.initializer() instanceof NewExpression creation)
                || creation.enclosingInstance().isPresent() || creation.anonymousClassBody().isPresent()
                || creation.arguments().size() != 2 || !capacity(source, creation.arguments().getFirst())
                || !(creation.arguments().get(1) instanceof FloatingLiteralExpression)
                || !(statements.get(1) instanceof TryStatement guarded) || guarded.finallyBlock().isPresent()
                || !cleanup(method, guarded, result.name())) return null;
        TypeSymbol created = resolver.resolve(creation.className(), source, creation.span()).type().orElse(null);
        if (created == null || !created.name().equals(source.name())) return null;
        List<CallableSymbol> constructors = escapes.boundTargets(method.linkageName(), creation.span(), created.simpleName());
        if (constructors.size() != 1 || escapes.summary(constructors.getFirst()).thisEscapes()) return null;
        FieldSymbol data = source.declaredFields().get("data");
        if (data == null || data.accessModifier() != AccessModifier.PRIVATE || !owned.isOwned(data)) return null;
        List<Statement> body = guarded.body().statements();
        if (body.isEmpty() || !(body.getLast() instanceof ReturnStatement returned)
                || !name(returned.value().orElse(null), result.name())) return null;
        boolean linked = source.name().equals("ironwood.ds.LinkedHashMap");
        if (linked) {
            if (body.size() != 3 || !(body.getFirst() instanceof LocalVariableDeclaration entry)
                    || !field(entry.initializer(), null, "head")
                    || !(body.get(1) instanceof WhileStatement loop)
                    || !entries(method, loop, entry.name(), result.name(), "after")) return null;
        } else {
            if (body.size() != 2 || !(body.getFirst() instanceof ForStatement loop)
                    || !(loop.initializer().orElse(null) instanceof LocalVariableDeclaration index)
                    || index.type().kind() != TypeName.Kind.INT || !zero(index.initializer())
                    || !(loop.condition().orElse(null) instanceof BinaryExpression condition)
                    || condition.operator() != BinaryOperator.LESS || !name(condition.left(), index.name())
                    || !(condition.right() instanceof FieldAccessExpression length)
                    || !length.fieldName().equals("length") || !field(length.receiver(), null, "data")
                    || loop.updates().size() != 1 || !(loop.updates().getFirst() instanceof UpdateExpression update)
                    || update.operator() != UpdateOperator.INCREMENT || !name(update.target(), index.name())
                    || !(loop.body() instanceof Block block) || block.statements().size() != 2
                    || !(block.statements().getFirst() instanceof LocalVariableDeclaration entry)
                    || !(entry.initializer() instanceof ArrayAccessExpression read)
                    || !field(read.array(), null, "data") || !name(read.index(), index.name())
                    || !(block.statements().get(1) instanceof WhileStatement entries)
                    || !entries(method, entries, entry.name(), result.name(), "next")) return null;
        }
        var items = new FreshBorrowingFactoryAnalysis.Input(ReturnOrigin.thisOrigin(), List.of(),
                source.selfType(), true, !source.name().equals("ironwood.ds.IdentityHashMap"));
        return new FreshBorrowingFactoryAnalysis.Result(method.returnType(), Map.of(), List.of(items));
    }

    private boolean entries(CallableSymbol method, WhileStatement loop, String entry, String result, String next) {
        if (!(loop.condition() instanceof BinaryExpression condition)
                || condition.operator() != BinaryOperator.NOT_EQUAL || !name(condition.left(), entry)
                || !(condition.right() instanceof NullLiteralExpression)
                || !(loop.body() instanceof Block block) || block.statements().size() != 2
                || !(block.statements().getFirst() instanceof ExpressionStatement insert)
                || !(insert.expression() instanceof CallExpression call)
                || !name(call.receiver().orElse(null), result) || call.arguments().size() != 2
                || !field(call.arguments().getFirst(), entry, "key") || !field(call.arguments().get(1), entry, "value")
                || !(block.statements().get(1) instanceof ExpressionStatement advance)
                || !(advance.expression() instanceof AssignmentExpression assignment)
                || assignment.operator() != AssignmentOperator.ASSIGN || !name(assignment.target(), entry)
                || !field(assignment.value(), entry, next)) return false;
        List<CallableSymbol> targets = escapes.boundTargets(method, call);
        return targets.size() == 1 && targets.getFirst().ownerType().equals(method.ownerType())
                && DataStructureSemantics.retainedArguments(targets.getFirst()).equals(Set.of(0, 1));
    }

    private boolean cleanup(CallableSymbol method, TryStatement guarded, String result) {
        Set<String> caught = new LinkedHashSet<>();
        for (CatchClause clause : guarded.catches()) {
            for (TypeName name : clause.types()) {
                TypeSymbol type = resolver.resolve(name.referenceName(), types.get(method.ownerType()), name.span())
                        .type().orElse(null);
                if (type == null) return false;
                caught.add(type.name());
            }
            List<Statement> body = clause.body().statements();
            if (body.size() != 2 || !(body.getFirst() instanceof FreeStatement free) || !name(free.value(), result)
                    || !(body.get(1) instanceof ThrowStatement failure) || !name(failure.value(), clause.variableName()))
                return false;
        }
        return caught.equals(Set.of("ironwood.lang.RuntimeException", "ironwood.lang.Error"));
    }

    private boolean capacity(TypeSymbol owner, Expression expression) {
        if (expression instanceof IntegerLiteralExpression) return true;
        if (expression instanceof BinaryExpression binary)
            return capacity(owner, binary.left()) && capacity(owner, binary.right());
        if (expression instanceof ConditionalExpression conditional)
            return capacity(owner, conditional.condition()) && capacity(owner, conditional.whenTrue())
                    && capacity(owner, conditional.whenFalse());
        if (!(expression instanceof FieldAccessExpression access) || !(access.receiver() instanceof ThisExpression))
            return false;
        FieldSymbol field = owner.declaredFields().get(access.fieldName());
        return field != null && !field.isStatic() && field.accessModifier() == AccessModifier.PRIVATE
                && field.type().equals(IrType.I32);
    }

    private static boolean name(Expression expression, String name) {
        return expression instanceof NameExpression variable && variable.name().equals(name);
    }

    private static boolean field(Expression expression, String receiver, String name) {
        return expression instanceof FieldAccessExpression field && field.fieldName().equals(name)
                && (receiver == null ? field.receiver() instanceof ThisExpression : name(field.receiver(), receiver));
    }

    private static boolean zero(Expression expression) {
        return expression instanceof IntegerLiteralExpression number && number.text().equals("0");
    }
}
