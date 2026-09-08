// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** The direct-superclass receiver used by {@code super.field} and {@code super.method(...)}. */
public record SuperExpression(SourceSpan span) implements Expression {
}
