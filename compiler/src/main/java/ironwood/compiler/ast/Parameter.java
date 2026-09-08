// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record Parameter(TypeName type, boolean isFinal,
                        String name, SourceSpan nameSpan, SourceSpan span) {
    public Parameter(TypeName type, String name, SourceSpan nameSpan, SourceSpan span) {
        this(type, false, name, nameSpan, span);
    }
}
