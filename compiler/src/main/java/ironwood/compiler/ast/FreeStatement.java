// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record FreeStatement(Expression value, SourceSpan span) implements Statement {
}
