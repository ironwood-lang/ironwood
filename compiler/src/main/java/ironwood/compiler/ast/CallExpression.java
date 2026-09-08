// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record CallExpression(Optional<Expression> receiver, List<TypeName> typeArguments,
                             String methodName, SourceSpan methodNameSpan,
                             List<Expression> arguments, SourceSpan span) implements Expression {
    public CallExpression {
        receiver = receiver == null ? Optional.empty() : receiver;
        typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        arguments = List.copyOf(arguments);
    }

    public CallExpression(Optional<Expression> receiver, String methodName,
                          SourceSpan methodNameSpan, List<Expression> arguments,
                          SourceSpan span) {
        this(receiver, List.of(), methodName, methodNameSpan, arguments, span);
    }
}
