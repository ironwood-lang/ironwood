// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** A lexically enclosing receiver such as {@code Outer.this}. */
public record QualifiedThisExpression(String typeName, SourceSpan typeNameSpan,
                                      SourceSpan span) implements Expression {
}
