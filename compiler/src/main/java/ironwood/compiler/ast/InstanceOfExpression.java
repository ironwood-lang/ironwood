// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record InstanceOfExpression(Expression operand, TypeName targetType,
                                   Optional<TypePatternBinding> binding,
                                   SourceSpan operatorSpan, SourceSpan span) implements Expression {
    public InstanceOfExpression {
        binding = binding == null ? Optional.empty() : binding;
    }
}
