// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record SwitchExpression(Expression selector, List<SwitchGroup> groups,
                               List<SwitchRule> rules, boolean arrowRules,
                               SourceSpan span) implements Expression {
    public SwitchExpression {
        groups = List.copyOf(groups);
        rules = List.copyOf(rules);
        if (arrowRules && !groups.isEmpty() || !arrowRules && !rules.isEmpty()) {
            throw new IllegalArgumentException("switch expression body forms cannot be mixed");
        }
    }
}
