// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record ForStatement(Optional<Statement> initializer,
                           Optional<Expression> condition,
                           List<Expression> updates,
                           Statement body,
                           SourceSpan span) implements Statement {
    public ForStatement {
        initializer = initializer == null ? Optional.empty() : initializer;
        condition = condition == null ? Optional.empty() : condition;
        updates = List.copyOf(updates);
    }
}
