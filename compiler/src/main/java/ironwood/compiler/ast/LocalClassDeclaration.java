// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** A named class whose declaration and scope are contained by one block. */
public record LocalClassDeclaration(ClassDeclaration declaration) implements Statement {
    @Override
    public SourceSpan span() {
        return declaration.span();
    }
}
