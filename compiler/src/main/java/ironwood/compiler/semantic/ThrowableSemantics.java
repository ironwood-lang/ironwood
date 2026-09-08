// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrType;

/** Audited dispatch points in Throwable's source-written description facade. */
final class ThrowableSemantics {
    private ThrowableSemantics() {
    }

    static boolean isThrowable(TypeSymbol type) {
        for (TypeSymbol current = type; current != null;
             current = current.superclass().orElse(null)) {
            if (current.name().equals("ironwood.lang.Throwable")) {
                return true;
            }
        }
        return false;
    }

    static boolean isDescription(CallableSymbol method) {
        return method.ownerType().equals("ironwood.lang.Throwable")
                && method.sourceName().equals("toString") && !method.isStatic()
                && method.parameterTypes().isEmpty()
                && method.returnType().equals(IrType.reference("ironwood.lang.String"));
    }

    static CallableSymbol messageGetter(TypeSymbol type) {
        CallableSymbol getter = stringGetter(type, "getLocalizedMessage");
        // The default localized getter delegates to this same receiver's getMessage.
        return getter != null && getter.ownerType().equals("ironwood.lang.Throwable")
                ? stringGetter(type, "getMessage") : getter;
    }

    private static CallableSymbol stringGetter(TypeSymbol type, String name) {
        for (TypeSymbol current = type; current != null;
             current = current.superclass().orElse(null)) {
            for (CallableSymbol method : current.declaredMethodsNamed(name)) {
                if (!method.isStatic() && method.parameterTypes().isEmpty()
                        && method.returnType().equals(IrType.reference("ironwood.lang.String"))) {
                    return method;
                }
            }
        }
        return null;
    }
}
