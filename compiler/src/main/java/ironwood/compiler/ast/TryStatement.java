// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record TryStatement(Block body, List<CatchClause> catches, Optional<Block> finallyBlock,
                           SourceSpan span) implements Statement {
    public TryStatement {
        catches = List.copyOf(catches);
        finallyBlock = finallyBlock == null ? Optional.empty() : finallyBlock;
    }
}
