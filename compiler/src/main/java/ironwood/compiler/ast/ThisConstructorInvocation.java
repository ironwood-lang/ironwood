// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record ThisConstructorInvocation(List<TypeName> typeArguments,
                                        List<Expression> arguments,
                                        SourceSpan span) implements Statement {
    public ThisConstructorInvocation {
        typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        arguments = List.copyOf(arguments);
    }

    public ThisConstructorInvocation(List<Expression> arguments, SourceSpan span) {
        this(List.of(), arguments, span);
    }
}
