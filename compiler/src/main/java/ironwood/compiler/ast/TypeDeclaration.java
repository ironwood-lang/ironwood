// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public sealed interface TypeDeclaration permits ClassDeclaration, InterfaceDeclaration {
    AccessModifier accessModifier();

    String name();

    List<TypeParameter> typeParameters();

    boolean isStatic();

    List<TypeDeclaration> memberTypes();

    SourceSpan nameSpan();

    SourceSpan span();
}
