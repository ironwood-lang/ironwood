// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record ContinueStatement(Optional<String> label, Optional<SourceSpan> labelSpan,
                                SourceSpan span) implements Statement {
    public ContinueStatement {
        label = label == null ? Optional.empty() : label;
        labelSpan = labelSpan == null ? Optional.empty() : labelSpan;
        if (label.isPresent() != labelSpan.isPresent()) {
            throw new IllegalArgumentException("continue label and span must be present together");
        }
    }

    public ContinueStatement(SourceSpan span) {
        this(Optional.empty(), Optional.empty(), span);
    }
}
