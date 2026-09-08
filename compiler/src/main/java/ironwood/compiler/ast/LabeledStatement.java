// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record LabeledStatement(String label, SourceSpan labelSpan,
                               Statement body, SourceSpan span) implements Statement {
}
