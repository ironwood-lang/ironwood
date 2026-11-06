// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record LocalVariableDeclaration(TypeName type, boolean isFinal,
                                       String name, SourceSpan nameSpan,
                                       Expression initializer, SourceSpan span) implements Statement {
    public LocalVariableDeclaration(TypeName type, String name, SourceSpan nameSpan,
                                    Expression initializer, SourceSpan span) {
        this(type, false, name, nameSpan, initializer, span);
    }
}
