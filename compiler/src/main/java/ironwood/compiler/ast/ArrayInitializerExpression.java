// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

/** A contextually typed declaration or nested array initializer. */
public record ArrayInitializerExpression(List<Expression> elements,
                                         SourceSpan span) implements Expression {
    public ArrayInitializerExpression {
        elements = List.copyOf(elements);
    }
}
