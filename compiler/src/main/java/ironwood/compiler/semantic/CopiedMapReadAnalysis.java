// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.*;
import ironwood.compiler.ir.IrType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded read-only lookup bodies over owned map buckets. Key callbacks are
 * deliberately separate from this shape proof and require call-site checks.
 * Reference returns must come from stored entry values, never the query key.
 */
final class CopiedMapReadAnalysis {
    private enum Role { PRIMITIVE, QUERY_KEY, STORED_KEY, ENTRY, VALUE, BUCKETS, NULL, UNKNOWN }
    record Read(boolean keyCallbacks) {}
    private final Map<String, TypeSymbol> types;
    private final EscapeSummaryAnalyzer escapes;
    private final OwnedArrayFieldAnalyzer owned;

    CopiedMapReadAnalysis(Map<String, TypeSymbol> types, EscapeSummaryAnalyzer escapes,
                          OwnedArrayFieldAnalyzer owned) {
        this.types = types;
        this.escapes = escapes;
        this.owned = owned;
    }

    Read prove(CallableSymbol method) {
        if (SetCopyFactoryAnalysis.isSet(IrType.reference(method.ownerType()))) return delegatedSetRead(method);
        if (method.isStatic() || method.isConstructor()
                || !MapCopyFactoryAnalysis.isMap(IrType.reference(method.ownerType()))) return null;
        if (method.parameterTypes().isEmpty() && method.returnType().isPrimitive()) {
            if (method.body().isEmpty() || method.body().orElseThrow().statements().size() != 1
                    || !(method.body().orElseThrow().statements().getFirst() instanceof ReturnStatement returned)
                    || returned.value().isEmpty()) return null;
            return new Context(method, Role.PRIMITIVE, new HashSet<>()).primitiveFieldExpression(returned.value().orElseThrow())
                    ? new Read(false) : null;
        }
        if (method.parameterTypes().size() != 1
                || !method.parameterTypes().getFirst().isReference()
                || !(method.returnType().isReference() || method.returnType().isPrimitive())) return null;
        Role returned = method.returnType().isReference() ? Role.VALUE : Role.PRIMITIVE;
        Context context = new Context(method, returned, new HashSet<>());
        if (!context.prove()) return null;
        return new Read(context.callbacks);
    }

    private Read delegatedSetRead(CallableSymbol method) {
        TypeSymbol owner = types.get(method.ownerType());
        if (owner == null || method.isStatic() || method.isConstructor() || method.body().isEmpty()
                || !method.returnType().isPrimitive() || method.body().orElseThrow().statements().size() != 1
                || !(method.body().orElseThrow().statements().getFirst() instanceof ReturnStatement returned)
                || !(returned.value().orElse(null) instanceof CallExpression call)
                || !(call.receiver().orElse(null) instanceof FieldAccessExpression field)
                || !(field.receiver() instanceof ThisExpression)) return null;
        FieldSymbol backing = owner.declaredFields().get(field.fieldName());
        if (backing == null || backing.accessModifier() != AccessModifier.PRIVATE || !owned.isOwned(backing)
                || !MapCopyFactoryAnalysis.isMap(backing.type()) || call.arguments().size() != method.parameters().size()) return null;
        for (int index = 0; index < call.arguments().size(); index++) {
            if (!(call.arguments().get(index) instanceof NameExpression argument)
                    || !argument.name().equals(method.parameters().get(index).name())) return null;
        }
        List<CallableSymbol> targets = escapes.boundTargets(method, call);
        return targets.size() == 1 && targets.getFirst().ownerType().equals(backing.type().referenceName())
                ? prove(targets.getFirst()) : null;
    }

    private final class Context {
        final CallableSymbol method;
        final Role returned;
        final Set<String> visiting;
        final Map<String, Role> locals = new HashMap<>();
        boolean callbacks;

        Context(CallableSymbol method, Role returned, Set<String> visiting) {
            this.method = method;
            this.returned = returned;
            this.visiting = visiting;
            for (int i = 0; i < method.parameters().size(); i++) {
                locals.put(method.parameters().get(i).name(), method.parameterTypes().get(i).isPrimitive()
                        ? Role.PRIMITIVE : Role.QUERY_KEY);
            }
        }

        boolean prove() {
            if (method.body().isEmpty() || !visiting.add(method.linkageName())) return false;
            try {
                for (Statement statement : method.body().orElseThrow().statements()) {
                    if (!statement(statement)) return false;
                }
                return true;
            } finally { visiting.remove(method.linkageName()); }
        }

        boolean statement(Statement statement) {
            if (statement instanceof Block block) {
                for (Statement child : block.statements()) if (!statement(child)) return false;
                return true;
            }
            if (statement instanceof LocalVariableDeclaration local) {
                Role role = expression(local.initializer());
                if (role == Role.UNKNOWN || locals.containsKey(local.name())) return false;
                locals.put(local.name(), role);
                return true;
            }
            if (statement instanceof ReturnStatement result) {
                Role role = result.value().isPresent() ? expression(result.value().orElseThrow()) : Role.UNKNOWN;
                return role == returned || role == Role.NULL && returned != Role.PRIMITIVE;
            }
            if (statement instanceof IfStatement branch) {
                return expression(branch.condition()) == Role.PRIMITIVE && statement(branch.thenBranch())
                        && (branch.elseBranch().isEmpty() || statement(branch.elseBranch().orElseThrow()));
            }
            if (statement instanceof WhileStatement loop) {
                return expression(loop.condition()) == Role.PRIMITIVE && statement(loop.body());
            }
            if (statement instanceof ExpressionStatement action) {
                if (action.expression() instanceof AssignmentExpression assignment) {
                    return assignment.operator() == AssignmentOperator.ASSIGN
                            && assignment.target() instanceof NameExpression local && locals.containsKey(local.name())
                            && expression(assignment.value()) == locals.get(local.name());
                }
                return action.expression() instanceof CallExpression call && nullGuard(call);
            }
            if (statement instanceof ThrowStatement failure) {
                if (!(failure.value() instanceof NewExpression creation)
                        || creation.enclosingInstance().isPresent() || creation.anonymousClassBody().isPresent()
                        || !(creation.className().equals("IllegalArgumentException")
                            || creation.className().equals("NullPointerException"))) return false;
                return creation.arguments().stream().allMatch(argument -> argument instanceof StringLiteralExpression);
            }
            return false;
        }

        Role expression(Expression expression) {
            if (expression instanceof NullLiteralExpression) return Role.NULL;
            if (expression instanceof IntegerLiteralExpression || expression instanceof BooleanLiteralExpression) return Role.PRIMITIVE;
            if (expression instanceof NameExpression name) {
                if (locals.containsKey(name.name())) return locals.get(name.name());
                return ownField(name.name());
            }
            if (expression instanceof FieldAccessExpression field) {
                if (field.receiver() instanceof ThisExpression) return ownField(field.fieldName());
                Role receiver = expression(field.receiver());
                if (receiver == Role.BUCKETS && field.fieldName().equals("length")) return Role.PRIMITIVE;
                if (receiver != Role.ENTRY) return Role.UNKNOWN;
                return switch (field.fieldName()) {
                    case "key" -> Role.STORED_KEY;
                    case "value" -> Role.VALUE;
                    case "hash" -> Role.PRIMITIVE;
                    case "next" -> Role.ENTRY;
                    default -> Role.UNKNOWN;
                };
            }
            if (expression instanceof ArrayAccessExpression read) {
                return expression(read.array()) == Role.BUCKETS && expression(read.index()) == Role.PRIMITIVE
                        ? Role.ENTRY : Role.UNKNOWN;
            }
            if (expression instanceof BinaryExpression binary) {
                Role left = expression(binary.left());
                Role right = expression(binary.right());
                if (left == Role.PRIMITIVE && right == Role.PRIMITIVE) return Role.PRIMITIVE;
                return (binary.operator() == BinaryOperator.EQUAL || binary.operator() == BinaryOperator.NOT_EQUAL)
                        && left != Role.UNKNOWN && right != Role.UNKNOWN ? Role.PRIMITIVE : Role.UNKNOWN;
            }
            if (expression instanceof UnaryExpression unary) {
                return expression(unary.operand()) == Role.PRIMITIVE ? Role.PRIMITIVE : Role.UNKNOWN;
            }
            if (expression instanceof CallExpression call) return call(call);
            return Role.UNKNOWN;
        }

        Role ownField(String name) {
            TypeSymbol owner = types.get(method.ownerType());
            FieldSymbol field = owner == null ? null : owner.declaredFields().get(name);
            if (field == null || field.isStatic() || field.accessModifier() != AccessModifier.PRIVATE) return Role.UNKNOWN;
            if (field.type().isPrimitive()) return Role.PRIMITIVE;
            return name.equals("data") && field.type().isArray() && field.type().elementType().isNominalReference()
                    && field.type().elementType().referenceName().equals(method.ownerType() + "Entry")
                    && owned.isOwned(field) ? Role.BUCKETS : Role.UNKNOWN;
        }

        boolean primitiveFieldExpression(Expression value) {
            if (value instanceof IntegerLiteralExpression || value instanceof BooleanLiteralExpression) return true;
            if (value instanceof NameExpression name) return ownField(name.name()) == Role.PRIMITIVE;
            if (value instanceof FieldAccessExpression field && field.receiver() instanceof ThisExpression)
                return ownField(field.fieldName()) == Role.PRIMITIVE;
            if (value instanceof BinaryExpression binary)
                return primitiveFieldExpression(binary.left()) && primitiveFieldExpression(binary.right());
            if (value instanceof UnaryExpression unary) return primitiveFieldExpression(unary.operand());
            return false;
        }

        Role call(CallExpression call) {
            List<CallableSymbol> targets = escapes.boundTargets(method, call);
            if (targets.isEmpty()) return Role.UNKNOWN;
            Role receiver = call.receiver().isPresent() ? expression(call.receiver().orElseThrow()) : Role.UNKNOWN;
            if (key(receiver) && call.methodName().equals("hashCode") && call.arguments().isEmpty()
                    && targets.stream().allMatch(target -> !target.isStatic() && target.returnType().equals(IrType.I32))) {
                callbacks = true;
                return Role.PRIMITIVE;
            }
            if (key(receiver) && call.methodName().equals("equals") && call.arguments().size() == 1
                    && key(expression(call.arguments().getFirst()))
                    && targets.stream().allMatch(target -> !target.isStatic() && target.returnType().equals(IrType.I1))) {
                callbacks = true;
                return Role.PRIMITIVE;
            }
            if (targets.size() != 1) return Role.UNKNOWN;
            CallableSymbol target = targets.getFirst();
            if (target.ownerType().equals("ironwood.lang.System") && target.isStatic()
                    && target.sourceName().equals("identityHashCode") && call.arguments().size() == 1
                    && key(expression(call.arguments().getFirst()))) return Role.PRIMITIVE;
            if (!target.ownerType().equals(method.ownerType()) || target.isStatic()
                    || target.accessModifier() != AccessModifier.PRIVATE
                    || call.receiver().isPresent() && !(call.receiver().orElseThrow() instanceof ThisExpression)
                    || call.arguments().size() != target.parameterTypes().size()) return Role.UNKNOWN;
            // Reference helper parameters carry keys only. Reclassifying entry
            // values as query keys would hide their callback exposure.
            for (int index = 0; index < call.arguments().size(); index++) {
                Role argument = expression(call.arguments().get(index));
                if (target.parameterTypes().get(index).isPrimitive() ? argument != Role.PRIMITIVE : !key(argument))
                    return Role.UNKNOWN;
            }
            Role role = target.returnType().isPrimitive() ? Role.PRIMITIVE
                    : target.returnType().isNominalReference()
                        && target.returnType().referenceName().equals(method.ownerType() + "Entry") ? Role.ENTRY : Role.UNKNOWN;
            if (role == Role.UNKNOWN) return Role.UNKNOWN;
            Context helper = new Context(target, role, visiting);
            if (!helper.prove()) return Role.UNKNOWN;
            callbacks |= helper.callbacks;
            return role;
        }

        boolean nullGuard(CallExpression call) {
            List<CallableSymbol> targets = escapes.boundTargets(method, call);
            if (targets.size() != 1 || call.arguments().size() != 1
                    || !key(expression(call.arguments().getFirst()))) return false;
            CallableSymbol target = targets.getFirst();
            if (!target.ownerType().equals(method.ownerType()) || target.isStatic()
                    || target.accessModifier() != AccessModifier.PRIVATE || target.body().isEmpty()
                    || target.parameters().size() != 1 || target.body().orElseThrow().statements().size() != 1
                    || call.receiver().isPresent() && !(call.receiver().orElseThrow() instanceof ThisExpression)) return false;
            Statement guard = target.body().orElseThrow().statements().getFirst();
            return guard instanceof IfStatement branch && branch.elseBranch().isEmpty()
                    && branch.condition() instanceof BinaryExpression condition && condition.operator() == BinaryOperator.EQUAL
                    && condition.left() instanceof NameExpression name && name.name().equals(target.parameters().getFirst().name())
                    && condition.right() instanceof NullLiteralExpression && branch.thenBranch() instanceof ThrowStatement
                    && new Context(target, Role.UNKNOWN, visiting).statement(guard);
        }
    }

    private static boolean key(Role role) { return role == Role.QUERY_KEY || role == Role.STORED_KEY; }
}
