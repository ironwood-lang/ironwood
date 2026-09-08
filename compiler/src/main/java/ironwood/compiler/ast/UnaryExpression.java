// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record UnaryExpression(UnaryOperator operator, Expression operand,
                              SourceSpan operatorSpan, SourceSpan span) implements Expression {
}
