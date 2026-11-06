// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.semantic;

/** Stable value-flow edges after definite initialization, without exclusive ownership. */
final class FinalFieldOrigins {
    private FinalFieldOrigins() {}

    static boolean isStable(FieldSymbol field) {
        return field != null && !field.isStatic() && field.isFinal() && field.type().isReference();
    }
}
