// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record ExpressionStatement(Expression expression, SourceSpan span) implements Statement {
}
