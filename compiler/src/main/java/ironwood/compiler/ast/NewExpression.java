// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record NewExpression(TypeName classType,
                            Optional<Expression> enclosingInstance,
                            List<TypeName> constructorTypeArguments,
                            boolean diamond,
                            List<Expression> arguments,
                            Optional<AnonymousClassBody> anonymousClassBody,
                            SourceSpan span) implements Expression {
    public NewExpression {
        enclosingInstance = enclosingInstance == null ? Optional.empty() : enclosingInstance;
        constructorTypeArguments = constructorTypeArguments == null
                ? List.of() : List.copyOf(constructorTypeArguments);
        arguments = List.copyOf(arguments);
        anonymousClassBody = anonymousClassBody == null
                ? Optional.empty() : anonymousClassBody;
    }

    public NewExpression(TypeName classType, Optional<Expression> enclosingInstance,
                         List<TypeName> constructorTypeArguments, boolean diamond,
                         List<Expression> arguments, SourceSpan span) {
        this(classType, enclosingInstance, constructorTypeArguments, diamond, arguments,
                Optional.empty(), span);
    }

    public NewExpression(TypeName classType, Optional<Expression> enclosingInstance,
                         List<Expression> arguments, SourceSpan span) {
        this(classType, enclosingInstance, List.of(), false, arguments, Optional.empty(), span);
    }

    public NewExpression(TypeName classType, List<Expression> arguments, SourceSpan span) {
        this(classType, Optional.empty(), List.of(), false, arguments, Optional.empty(), span);
    }

    public NewExpression(String className, SourceSpan classNameSpan,
                         List<Expression> arguments, SourceSpan span) {
        this(TypeName.reference(className, classNameSpan), Optional.empty(),
                List.of(), false, arguments, Optional.empty(), span);
    }

    public String className() {
        return classType.referenceName();
    }

    public SourceSpan classNameSpan() {
        return classType.span();
    }
}
