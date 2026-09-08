// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record WhileStatement(Expression condition, Statement body, SourceSpan span) implements Statement {
}
