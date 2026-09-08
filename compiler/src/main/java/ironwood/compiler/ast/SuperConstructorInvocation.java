// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record SuperConstructorInvocation(Optional<Expression> enclosingInstance,
                                         List<TypeName> typeArguments,
                                         List<Expression> arguments,
                                         SourceSpan span) implements Statement {
    public SuperConstructorInvocation {
        enclosingInstance = enclosingInstance == null ? Optional.empty() : enclosingInstance;
        typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        arguments = List.copyOf(arguments);
    }

    public SuperConstructorInvocation(Optional<Expression> enclosingInstance,
                                      List<Expression> arguments, SourceSpan span) {
        this(enclosingInstance, List.of(), arguments, span);
    }

    public SuperConstructorInvocation(List<Expression> arguments, SourceSpan span) {
        this(Optional.empty(), List.of(), arguments, span);
    }
}
