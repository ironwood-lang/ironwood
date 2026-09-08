// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record AssignmentStatement(Expression target, SourceSpan equalsSpan, Expression value,
                                  SourceSpan span) implements Statement {
}
