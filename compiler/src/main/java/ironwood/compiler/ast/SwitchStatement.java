// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record SwitchStatement(Expression selector, List<SwitchGroup> groups,
                              SourceSpan span) implements Statement {
    public SwitchStatement {
        groups = List.copyOf(groups);
    }
}
