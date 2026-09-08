// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record SwitchRule(List<SwitchLabel> labels, SwitchRuleBody body,
                         SourceSpan span) {
    public SwitchRule {
        labels = List.copyOf(labels);
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("switch rule requires at least one label");
        }
    }
}
