// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.lexer;

import ironwood.compiler.source.SourceSpan;

/** Source trivia retained only when a documentation consumer requests it. */
public record DocumentationComment(String text, SourceSpan span) {
}
