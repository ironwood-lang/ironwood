// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record TypePatternBinding(String name, SourceSpan nameSpan, boolean isFinal) {
    public TypePatternBinding {
        if (name == null || name.isBlank() || nameSpan == null) {
            throw new IllegalArgumentException("type-pattern binding requires a name and source span");
        }
    }
}
