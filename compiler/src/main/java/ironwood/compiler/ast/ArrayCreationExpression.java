// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record ArrayCreationExpression(TypeName elementType, Optional<Expression> length,
                                      Optional<ArrayInitializerExpression> initializer,
                                      SourceSpan span) implements Expression {
    public ArrayCreationExpression {
        length = length == null ? Optional.empty() : length;
        initializer = initializer == null ? Optional.empty() : initializer;
        if (length.isPresent() == initializer.isPresent()) {
            throw new IllegalArgumentException(
                    "array creation requires exactly one of length or initializer");
        }
    }

    public ArrayCreationExpression(TypeName elementType, Expression length,
                                   SourceSpan span) {
        this(elementType, Optional.of(length), Optional.empty(), span);
    }
}
