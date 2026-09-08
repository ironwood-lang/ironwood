// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record AssignmentExpression(Expression target, AssignmentOperator operator,
                                   SourceSpan operatorSpan, Expression value,
                                   SourceSpan span) implements Expression {
}
