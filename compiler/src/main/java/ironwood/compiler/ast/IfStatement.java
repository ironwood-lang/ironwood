// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record IfStatement(Expression condition, Statement thenBranch,
                          Optional<Statement> elseBranch, SourceSpan span) implements Statement {
    public IfStatement {
        elseBranch = elseBranch == null ? Optional.empty() : elseBranch;
    }
}
