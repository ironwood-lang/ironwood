// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record ConstructorDeclaration(AccessModifier accessModifier, List<TypeParameter> typeParameters,
                                     String name,
                                     SourceSpan nameSpan, List<Parameter> parameters,
                                     List<TypeName> thrownTypes,
                                     Optional<SuperConstructorInvocation> superInvocation,
                                     Optional<ThisConstructorInvocation> thisInvocation,
                                     Block body, SourceSpan span) {
    public ConstructorDeclaration {
        typeParameters = typeParameters == null ? List.of() : List.copyOf(typeParameters);
        parameters = List.copyOf(parameters);
        thrownTypes = thrownTypes == null ? List.of() : List.copyOf(thrownTypes);
        superInvocation = superInvocation == null ? Optional.empty() : superInvocation;
        thisInvocation = thisInvocation == null ? Optional.empty() : thisInvocation;
    }

    public ConstructorDeclaration(AccessModifier accessModifier, String name,
                                  SourceSpan nameSpan, List<Parameter> parameters,
                                  Optional<SuperConstructorInvocation> superInvocation,
                                  Optional<ThisConstructorInvocation> thisInvocation,
                                  Block body, SourceSpan span) {
        this(accessModifier, List.of(), name, nameSpan, parameters, List.of(), superInvocation,
                thisInvocation, body, span);
    }
}
