// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ir.IrField;
import ironwood.compiler.ir.IrStaticField;
import ironwood.compiler.ir.IrType;

record FieldSymbol(FieldDeclaration declaration, AccessModifier accessModifier,
                   IrType type, String ownerClass, IrField irField,
                   IrStaticField staticField, ConstantValue constantValue) {
    boolean isStatic() {
        return declaration.isStatic();
    }

    boolean isFinal() {
        return declaration.isFinal();
    }

    FieldSymbol withStaticField(IrStaticField value, ConstantValue constant) {
        return new FieldSymbol(declaration, accessModifier, type, ownerClass,
                null, value, constant);
    }

    FieldSymbol substitute(java.util.Map<String, IrType> substitutions) {
        if (substitutions.isEmpty() || isStatic()) {
            return this;
        }
        return new FieldSymbol(declaration, accessModifier, type.substitute(substitutions),
                ownerClass, irField, staticField, constantValue);
    }
}
