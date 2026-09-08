// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

/** The source-level or compiler-synthesized role of a callable. */
public enum IrCallableKind {
    METHOD,
    CONSTRUCTOR,
    DESTRUCTOR,
    CLASS_INITIALIZER,
    CONSTRUCTOR_ROLLBACK
}
