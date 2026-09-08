// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

/** The source conversion applied to one value in a dynamic String concatenation. */
public enum IrStringConcatPartKind {
    STRING,
    BOOLEAN,
    CHARACTER,
    INTEGER,
    FLOAT,
    DOUBLE
}
