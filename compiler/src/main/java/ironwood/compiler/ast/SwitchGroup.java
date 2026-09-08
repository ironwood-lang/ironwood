// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record SwitchGroup(List<SwitchLabel> labels, List<Statement> statements,
                          SourceSpan span) {
    public SwitchGroup {
        labels = List.copyOf(labels);
        statements = List.copyOf(statements);
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("switch group requires at least one label");
        }
    }
}
