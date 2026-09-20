// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** Binds a local for proven-safe reclamation when its explicit block exits. */
public record DeferredFreeStatement(NameExpression target, SourceSpan span) implements Statement {
}
