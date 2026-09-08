// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record Block(List<Statement> statements, SourceSpan span)
        implements Statement, InstanceInitialization, StaticInitialization {
    public Block {
        statements = List.copyOf(statements);
    }
}
