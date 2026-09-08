// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** A closed-world static field and its native preparation value. */
public record IrStaticField(String ownerClass, String name, IrType type, boolean isFinal,
                            boolean compileTimeConstant, boolean preinitializedIntrinsic,
                            IrOperand initialValue, SourceSpan sourceSpan) {
    public IrStaticField {
        if (type.equals(IrType.VOID) || type.equals(IrType.NULL)
                || type.equals(IrType.EXCEPTION) || type.isTypeParameter()) {
            throw new IllegalArgumentException("invalid static field type " + type.displayName());
        }
        if (!initialValue.type().equals(type)
                && !(initialValue instanceof IrNull && type.isReference())) {
            throw new IllegalArgumentException("static initializer type mismatch for "
                    + ownerClass + "." + name);
        }
    }

    public boolean triggersInitialization() {
        return !compileTimeConstant && !preinitializedIntrinsic;
    }

    public boolean constantStorage() {
        return isFinal && !triggersInitialization();
    }
}
