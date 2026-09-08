// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record EnhancedForStatement(TypeName variableType, boolean variableFinal,
                                   String variableName, SourceSpan variableNameSpan,
                                   Expression iterable, Statement body,
                                   SourceSpan span) implements Statement {
}
