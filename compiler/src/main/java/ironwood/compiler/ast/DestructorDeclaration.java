// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** A class destructor. Superclass chaining is compiler-owned and implicit. */
public record DestructorDeclaration(Block body, SourceSpan keywordSpan,
                                    SourceSpan span) {
}
