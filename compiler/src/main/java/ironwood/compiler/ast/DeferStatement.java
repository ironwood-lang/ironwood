// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** Captures a void invocation for cleanup at the end of its explicit source block. */
public record DeferStatement(CallExpression call, SourceSpan span) implements Statement {
}
