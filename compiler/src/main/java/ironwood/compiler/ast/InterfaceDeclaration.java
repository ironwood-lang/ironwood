// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record InterfaceDeclaration(
        AccessModifier accessModifier,
        boolean isStatic,
        String name,
        SourceSpan nameSpan,
        List<TypeParameter> typeParameters,
        List<TypeReference> extendedInterfaces,
        List<FieldDeclaration> fields,
        List<InterfaceMethodDeclaration> methods,
        List<TypeDeclaration> memberTypes,
        SourceSpan span
) implements TypeDeclaration {
    public InterfaceDeclaration {
        typeParameters = List.copyOf(typeParameters);
        extendedInterfaces = List.copyOf(extendedInterfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        memberTypes = List.copyOf(memberTypes);
    }

    public InterfaceDeclaration(AccessModifier accessModifier, String name, SourceSpan nameSpan,
                                List<TypeReference> extendedInterfaces,
                                List<InterfaceMethodDeclaration> methods, SourceSpan span) {
        this(accessModifier, false, name, nameSpan, List.of(), extendedInterfaces, List.of(), methods,
                List.of(), span);
    }
}
