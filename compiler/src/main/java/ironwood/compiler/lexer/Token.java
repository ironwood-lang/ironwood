// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.lexer;

import ironwood.compiler.source.SourceSpan;

public record Token(TokenKind kind, String lexeme, SourceSpan span) {
}
