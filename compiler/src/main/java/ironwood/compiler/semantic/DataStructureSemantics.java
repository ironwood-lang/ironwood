// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.ThisExpression;
import ironwood.compiler.ir.IrType;
import java.util.List;
import java.util.Set;

/** Audited internal entry ownership of the bundled data structures. */
final class DataStructureSemantics {
    private static final Set<String> BORROWING_CONTAINERS = Set.of(
            "ironwood.ds.ArrayList", "ironwood.ds.LinkedList", "ironwood.ds.ArrayLinkedList",
            "ironwood.ds.HashMap", "ironwood.ds.IdentityHashMap", "ironwood.ds.LinkedHashMap",
            "ironwood.ds.IntMap", "ironwood.ds.LongMap", "ironwood.ds.ByteMap", "ironwood.ds.CharMap",
            "ironwood.ds.CharSequenceMap",
            "ironwood.ds.ByteBufferMap", "ironwood.ds.HashSet", "ironwood.ds.IdentityHashSet",
            "ironwood.ds.LinkedHashSet");
    private static final Set<String> CONTAINERS = Set.of(
            "ironwood.ds.HashMap", "ironwood.ds.IdentityHashMap", "ironwood.ds.LinkedHashMap",
            "ironwood.ds.IntMap", "ironwood.ds.LongMap", "ironwood.ds.CharSequenceMap",
            "ironwood.ds.ByteBufferMap", "ironwood.ds.LinkedList",
            "ironwood.ds.IntLinkedList", "ironwood.ds.LongLinkedList");

    private DataStructureSemantics() {}

    static boolean isBorrowingContainer(String type) {
        return BORROWING_CONTAINERS.contains(type);
    }

    static boolean isSizeQuery(CallableSymbol method) {
        return !method.isStatic() && method.parameterTypes().isEmpty()
                && Set.of("size", "isEmpty", "getInitialCapacity", "getGrowthFactor",
                        "getLoadFactor", "getMaxKeyLength", "getArraySize")
                        .contains(method.sourceName());
    }

    static boolean clearsBorrowedItems(CallableSymbol method) {
        // ArrayLinkedList exposes unchecked package-level arrayElement reads.
        // Its inactive slots therefore remain loans until destruction.
        return isBorrowingContainer(method.ownerType()) && !method.isStatic()
                && method.sourceName().equals("clear") && method.parameterTypes().isEmpty();
    }

    static Set<Integer> retainedArguments(CallableSymbol method) {
        if (method.isStatic() || !isBorrowingContainer(method.ownerType())) { return Set.of(); }
        String owner = method.ownerType();
        String name = method.sourceName();
        int count = method.parameterTypes().size();
        if (owner.endsWith("List")) {
            if (Set.of("add", "addFirst", "addLast").contains(name) && count == 1) {
                return Set.of(0);
            }
            if (owner.equals("ironwood.ds.ArrayList") && name.equals("insert") && count == 2) {
                return Set.of(1);
            }
            if (owner.equals("ironwood.ds.ArrayList") && name.equals("set") && count == 2) {
                return Set.of(1);
            }
        } else if (owner.endsWith("Set") && name.equals("add") && count == 1) {
            return Set.of(0);
        } else if (owner.endsWith("Map") && name.equals("put") && count >= 2) {
            return Set.of("ironwood.ds.HashMap", "ironwood.ds.IdentityHashMap", "ironwood.ds.LinkedHashMap")
                    .contains(owner) ? Set.of(0, 1) : Set.of(count - 1);
        }
        return Set.of();
    }

    static boolean isRemoval(CallableSymbol method) {
        if (method.isStatic() || !isBorrowingContainer(method.ownerType())) { return false; }
        if (method.sourceName().equals("removeFirst") || method.sourceName().equals("removeLast")) {
            return method.parameterTypes().isEmpty();
        }
        if (!method.sourceName().equals("remove")) { return false; }
        return method.ownerType().endsWith("Map") || method.ownerType().endsWith("Set")
                || method.ownerType().equals("ironwood.ds.ArrayList")
                && method.parameterTypes().equals(List.of(IrType.I32));
    }

    static boolean invokesKeyCallbacks(CallableSymbol method) {
        return Set.of("ironwood.ds.HashMap", "ironwood.ds.LinkedHashMap",
                "ironwood.ds.HashSet", "ironwood.ds.LinkedHashSet").contains(method.ownerType());
    }

    static boolean isListViewFactory(CallableSymbol method) {
        if (!method.ownerType().equals("ironwood.ds.Collections") || !method.isStatic()
                || !method.sourceName().equals("unmodifiableList")
                || method.parameters().size() != 1
                || !method.parameterTypes().getFirst().isNominalReference()
                || !method.parameterTypes().getFirst().referenceName().equals("ironwood.ds.ArrayList")
                || !method.returnType().isNominalReference()
                || !method.returnType().referenceName().equals("ironwood.ds.UnmodifiableList")) {
            return false;
        }
        var body = method.body().orElse(null);
        return body != null && body.statements().size() == 1
                && body.statements().getFirst() instanceof ReturnStatement returned
                && returned.value().orElse(null) instanceof NewExpression created
                && Set.of("UnmodifiableList", "ironwood.ds.UnmodifiableList").contains(created.className())
                && created.enclosingInstance().isEmpty() && created.anonymousClassBody().isEmpty()
                && created.arguments().size() == 1
                && created.arguments().getFirst() instanceof NameExpression argument
                && argument.name().equals(method.parameters().getFirst().name());
    }

    static boolean borrowsReceiver(CallableSymbol method) {
        return CONTAINERS.contains(method.ownerType()) && !method.isStatic()
                && !method.isConstructor();
    }

    static boolean isEntryPool(FieldSymbol field) {
        return CONTAINERS.contains(field.ownerClass()) && privateFinal(field)
                && field.declaration().name().equals("entryPool")
                && field.type().isNominalReference()
                && PoolSemantics.isPool(field.type().referenceName());
    }

    static boolean isEntryBuilder(FieldSymbol field) {
        return CONTAINERS.contains(field.ownerClass()) && privateFinal(field)
                && field.declaration().name().equals("entryBuilder");
    }

    private static boolean privateFinal(FieldSymbol field) {
        return !field.isStatic() && field.isFinal()
                && field.accessModifier() == AccessModifier.PRIVATE;
    }

    static String borrowedResultType(CallableSymbol method) {
        if (CONTAINERS.contains(method.ownerType()) && !method.isStatic()
                && method.returnType().isNominalReference()
                && method.returnType().referenceName().equals(method.ownerType() + "Entry")) {
            return method.returnType().referenceName();
        }
        if (!method.isStatic() && method.sourceName().equals("getCurrIteratorKey")
                && method.parameterTypes().isEmpty()) {
            return switch (method.ownerType()) {
                case "ironwood.ds.CharSequenceMap" -> "ironwood.lang.StringBuilder";
                case "ironwood.ds.ByteBufferMap" -> "ironwood.nio.ByteBuffer";
                default -> null;
            };
        }
        return null;
    }

    static boolean hasOrderedCleanup(TypeSymbol owner) {
        // The pool borrows the builder. Reverse-layout constructor rollback must
        // therefore destroy the pool first, as must the explicit destructor.
        List<String> fields = owner.layoutFields().stream().map(field -> field.name()).toList();
        if (fields.indexOf("entryBuilder") < 0
                || fields.indexOf("entryBuilder") >= fields.indexOf("entryPool")) {
            return false;
        }
        var destructor = owner.destructor().flatMap(CallableSymbol::body).orElse(null);
        if (destructor == null) { return false; }
        List<String> expected = owner.name().endsWith("List")
                ? List.of("reusableIterator", "entryPool", "entryBuilder")
                : List.of("reusableIterator", "data", "entryPool", "entryBuilder");
        if (destructor.statements().size() != expected.size()) { return false; }
        for (int index = 0; index < expected.size(); index++) {
            if (!(destructor.statements().get(index) instanceof FreeStatement free)
                    || !freedFieldName(free).equals(expected.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static String freedFieldName(FreeStatement free) {
        if (free.value() instanceof NameExpression name) {
            return name.name();
        }
        if (free.value() instanceof FieldAccessExpression access
                && access.receiver() instanceof ThisExpression) {
            return access.fieldName();
        }
        return "";
    }
}
