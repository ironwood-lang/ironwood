// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record FieldAccessExpression(Expression receiver, String fieldName,
                                    SourceSpan fieldNameSpan, SourceSpan span) implements Expression {
}
