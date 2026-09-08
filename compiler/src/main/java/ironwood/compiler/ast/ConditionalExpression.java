// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record ConditionalExpression(Expression condition, Expression whenTrue,
                                    Expression whenFalse, SourceSpan questionSpan,
                                    SourceSpan span) implements Expression {
}
