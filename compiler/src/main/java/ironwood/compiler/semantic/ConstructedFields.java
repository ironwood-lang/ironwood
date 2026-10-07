// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrBasicBlock;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrFieldStoreInstruction;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrNull;
import ironwood.compiler.ir.IrParameter;
import ironwood.compiler.ir.IrValueReference;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Final reference fields that hold a non-null value from the end of construction until
 * the destructor of their class frees them, and the destructor null checks they make
 * redundant (D289).
 *
 * <p>Every constructor that completes normally assigns each blank final field of its
 * class exactly once, and a field initializer is a store in every constructor that does
 * not delegate; the language rejects programs that do otherwise. A final field
 * therefore holds a non-null value after construction when each of its stores is a
 * constructor of its class storing into its own object a value proven non-null at that
 * point. The only other store a final field may have is the null that its class's
 * destructor stores when it frees an owned field.
 *
 * <p>A destructor runs only on a fully constructed object, and the destructors of
 * subclasses that run before it cannot store the fields of its class or of its
 * superclasses. At its entry each such field of its object is non-null until its own
 * free stores null, so each destructor is solved again from those entry facts, which
 * can only remove checks. Methods are not covered, since one may run during
 * construction or after the free.
 */
final class ConstructedFields {
    private record Field(String owner, String name) {
    }

    private ConstructedFields() {
    }

    /** Replaces each destructor with one that omits the checks its constructed fields prove. */
    static void omitDestructorChecks(List<IrFunction> functions, Map<String, TypeSymbol> types) {
        if (functions.stream().noneMatch(function -> function.kind() == IrCallableKind.DESTRUCTOR)) {
            return;
        }
        Set<Field> constructed = constructedFields(functions, types);
        if (constructed.isEmpty()) {
            return;
        }
        for (int index = 0; index < functions.size(); index++) {
            IrFunction function = functions.get(index);
            IrValueReference self = self(function);
            if (function.kind() != IrCallableKind.DESTRUCTOR || self == null) {
                continue;
            }
            Set<RedundantNullChecks.FieldKey> entry = new LinkedHashSet<>();
            for (TypeSymbol type = types.get(function.ownerClass()); type != null;
                 type = type.superclass().orElse(null)) {
                for (FieldSymbol field : type.declaredFields().values()) {
                    if (field.irField() != null && constructed.contains(
                            new Field(field.irField().ownerClass(), field.irField().name()))) {
                        entry.add(new RedundantNullChecks.FieldKey(self,
                                field.irField().ownerClass(), field.irField().name()));
                    }
                }
            }
            if (entry.isEmpty()) {
                continue;
            }
            List<IrBasicBlock> blocks = RedundantNullChecks.omit(function.blocks(), Set.of(self), entry);
            if (blocks != function.blocks()) {
                functions.set(index, new IrFunction(function.ownerClass(), function.sourceName(),
                        function.linkageName(), function.returnType(), function.parameters(), blocks,
                        function.sourceSpan(), function.sourceFileName(), function.kind()));
            }
        }
    }

    /** The final reference instance fields whose every store keeps them non-null after construction. */
    private static Set<Field> constructedFields(List<IrFunction> functions, Map<String, TypeSymbol> types) {
        Set<Field> result = new LinkedHashSet<>();
        for (TypeSymbol type : types.values()) {
            for (FieldSymbol field : type.declaredFields().values()) {
                if (!field.isStatic() && field.isFinal() && field.irField() != null
                        && field.type().isReference()) {
                    result.add(new Field(field.irField().ownerClass(), field.irField().name()));
                }
            }
        }
        for (IrFunction function : functions) {
            RedundantNullChecks facts = null;
            for (IrBasicBlock block : function.blocks()) {
                for (int index = 0; index < block.instructions().size(); index++) {
                    if (!(block.instructions().get(index) instanceof IrFieldStoreInstruction store)) {
                        continue;
                    }
                    Field field = new Field(store.field().ownerClass(), store.field().name());
                    if (!result.contains(field)) {
                        continue;
                    }
                    boolean own = function.ownerClass().equals(field.owner());
                    if (own && function.kind() == IrCallableKind.DESTRUCTOR
                            && store.value() instanceof IrNull) {
                        continue;
                    }
                    IrValueReference self = self(function);
                    if (own && function.kind() == IrCallableKind.CONSTRUCTOR && self != null) {
                        if (facts == null) {
                            facts = RedundantNullChecks.solved(function.blocks(), Set.of(self));
                        }
                        if (facts.sameObject(store.receiver(), self)
                                && facts.knownBefore(block.label(), index, store.value())) {
                            continue;
                        }
                    }
                    result.remove(field);
                }
            }
        }
        return result;
    }

    private static IrValueReference self(IrFunction function) {
        return function.parameters().isEmpty() || !function.parameters().getFirst().name().equals("this")
                ? null : function.parameters().getFirst().value();
    }
}
