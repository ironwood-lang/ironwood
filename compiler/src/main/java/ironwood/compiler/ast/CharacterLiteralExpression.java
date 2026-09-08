// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** One unsigned UTF-16 code unit. */
public record CharacterLiteralExpression(int value, SourceSpan span) implements Expression {
    public CharacterLiteralExpression {
        if (value < Character.MIN_VALUE || value > Character.MAX_VALUE) {
            throw new IllegalArgumentException("character literal must be one UTF-16 code unit");
        }
    }
}
