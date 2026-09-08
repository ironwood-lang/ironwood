// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** Compiler-owned enum construction action inserted at the start of {@code <clinit>}. */
public record EnumConstantInitialization(EnumConstant constant, SourceSpan span)
        implements Statement {
}
