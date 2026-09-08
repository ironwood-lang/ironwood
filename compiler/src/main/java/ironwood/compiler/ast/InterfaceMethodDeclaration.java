// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record InterfaceMethodDeclaration(
        AccessModifier accessModifier,
        InterfaceMethodKind kind,
        boolean hasOverrideDirective,
        List<TypeParameter> typeParameters,
        TypeName returnType,
        String name,
        SourceSpan nameSpan,
        List<Parameter> parameters,
        List<TypeName> thrownTypes,
        Optional<Block> body,
        SourceSpan span
) {
    public InterfaceMethodDeclaration {
        typeParameters = typeParameters == null ? List.of() : List.copyOf(typeParameters);
        parameters = List.copyOf(parameters);
        thrownTypes = thrownTypes == null ? List.of() : List.copyOf(thrownTypes);
        body = body == null ? Optional.empty() : body;
    }

    public InterfaceMethodDeclaration(AccessModifier accessModifier, InterfaceMethodKind kind,
                                      TypeName returnType, String name, SourceSpan nameSpan,
                                      List<Parameter> parameters, Optional<Block> body,
                                      SourceSpan span) {
        this(accessModifier, kind, false, List.of(), returnType, name, nameSpan,
                parameters, List.of(), body, span);
    }

    public boolean isStatic() {
        return kind == InterfaceMethodKind.STATIC || kind == InterfaceMethodKind.PRIVATE_STATIC;
    }

    public boolean isAbstract() {
        return kind == InterfaceMethodKind.ABSTRACT;
    }

    public boolean isDefault() {
        return kind == InterfaceMethodKind.DEFAULT;
    }
}
