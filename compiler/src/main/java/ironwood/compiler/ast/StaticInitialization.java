// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** A source-ordered static field initializer or static initializer block. */
public sealed interface StaticInitialization permits Block, FieldDeclaration {
    SourceSpan span();
}
