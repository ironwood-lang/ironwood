// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record BreakStatement(Optional<String> label, Optional<SourceSpan> labelSpan,
                             SourceSpan span) implements Statement {
    public BreakStatement {
        label = label == null ? Optional.empty() : label;
        labelSpan = labelSpan == null ? Optional.empty() : labelSpan;
        if (label.isPresent() != labelSpan.isPresent()) {
            throw new IllegalArgumentException("break label and span must be present together");
        }
    }

    public BreakStatement(SourceSpan span) {
        this(Optional.empty(), Optional.empty(), span);
    }
}
