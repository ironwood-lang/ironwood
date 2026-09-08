// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record TypeParameter(String name, List<TypeName> upperBounds, SourceSpan span) {
    public TypeParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("type parameter requires a name");
        }
        upperBounds = upperBounds == null ? List.of() : List.copyOf(upperBounds);
    }

    public TypeParameter(String name, SourceSpan span) {
        this(name, List.of(), span);
    }

    public List<TypeName> bounds() {
        return upperBounds;
    }
}
