// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record CatchClause(List<TypeName> types, boolean isFinal,
                          String variableName, SourceSpan variableNameSpan,
                          Block body, SourceSpan span) {
    public CatchClause {
        types = List.copyOf(types);
        if (types.isEmpty()) {
            throw new IllegalArgumentException("catch clause requires at least one type");
        }
    }

    public CatchClause(TypeName type, String variableName, SourceSpan variableNameSpan,
                       Block body, SourceSpan span) {
        this(List.of(type), false, variableName, variableNameSpan, body, span);
    }

    public boolean isMultiCatch() {
        return types.size() > 1;
    }
}
