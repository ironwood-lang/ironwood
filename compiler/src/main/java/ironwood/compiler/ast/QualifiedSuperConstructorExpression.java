// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

/** Parser-only expression form normalized to a constructor invocation statement. */
public record QualifiedSuperConstructorExpression(Expression enclosingInstance,
                                                  List<TypeName> typeArguments,
                                                  List<Expression> arguments,
                                                  SourceSpan span) implements Expression {
    public QualifiedSuperConstructorExpression {
        typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        arguments = List.copyOf(arguments);
    }

    public QualifiedSuperConstructorExpression(Expression enclosingInstance,
                                               List<Expression> arguments,
                                               SourceSpan span) {
        this(enclosingInstance, List.of(), arguments, span);
    }
}
