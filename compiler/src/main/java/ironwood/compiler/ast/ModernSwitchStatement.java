// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record ModernSwitchStatement(Expression selector, List<SwitchRule> rules,
                                    SourceSpan span) implements Statement {
    public ModernSwitchStatement {
        rules = List.copyOf(rules);
    }
}
