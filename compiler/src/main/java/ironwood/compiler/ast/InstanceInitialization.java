// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** A source-ordered instance field initializer or instance initializer block. */
public sealed interface InstanceInitialization permits Block, FieldDeclaration {
    SourceSpan span();
}
