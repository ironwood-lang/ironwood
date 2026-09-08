// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

/** A direct-superinterface receiver such as {@code Parent.super}. */
public record InterfaceSuperExpression(String interfaceName, SourceSpan interfaceNameSpan,
                                       SourceSpan span) implements Expression {
}
