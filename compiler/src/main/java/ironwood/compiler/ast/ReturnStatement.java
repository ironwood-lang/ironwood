// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record ReturnStatement(Optional<Expression> value, SourceSpan span) implements Statement {
    public ReturnStatement {
        value = value == null ? Optional.empty() : value;
    }
}
