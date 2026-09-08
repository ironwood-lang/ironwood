// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record BinaryExpression(Expression left, BinaryOperator operator, Expression right,
                               SourceSpan operatorSpan, SourceSpan span) implements Expression {
}
