// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record SwitchLabel(Optional<Expression> value, SourceSpan span) {
    public SwitchLabel {
        value = value == null ? Optional.empty() : value;
    }

    public boolean isDefault() {
        return value.isEmpty();
    }
}
