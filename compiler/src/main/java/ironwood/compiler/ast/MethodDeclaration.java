// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record MethodDeclaration(
        AccessModifier accessModifier,
        boolean isStatic,
        boolean isAbstract,
        boolean isFinal,
        boolean hasOverrideDirective,
        boolean hasTestDirective,
        List<TypeParameter> typeParameters,
        TypeName returnType,
        String name,
        SourceSpan nameSpan,
        List<Parameter> parameters,
        List<TypeName> thrownTypes,
        Optional<Block> body,
        SourceSpan span
) {
    public MethodDeclaration {
        typeParameters = typeParameters == null ? List.of() : List.copyOf(typeParameters);
        parameters = List.copyOf(parameters);
        thrownTypes = thrownTypes == null ? List.of() : List.copyOf(thrownTypes);
        body = body == null ? Optional.empty() : body;
    }

    public MethodDeclaration(AccessModifier accessModifier, boolean isStatic,
                             boolean isAbstract, boolean isFinal,
                             TypeName returnType, String name, SourceSpan nameSpan,
                             List<Parameter> parameters, Optional<Block> body,
                             SourceSpan span) {
        this(accessModifier, isStatic, isAbstract, isFinal, false, false, List.of(),
                returnType, name, nameSpan, parameters, List.of(), body, span);
    }

    public boolean isPublic() {
        return accessModifier == AccessModifier.PUBLIC;
    }
}
