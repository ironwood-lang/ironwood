// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record FieldDeclaration(AccessModifier accessModifier, boolean isStatic, boolean isFinal,
                               TypeName type, String name, SourceSpan nameSpan,
                               Optional<Expression> initializer, SourceSpan span)
        implements InstanceInitialization, StaticInitialization {
    public FieldDeclaration {
        initializer = initializer == null ? Optional.empty() : initializer;
    }
}
