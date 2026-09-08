// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.Expression;
import ironwood.compiler.ir.IrType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The type-only, evaluation-free plan for one expression tree. */
record ExpressionTypePlan(Expression expression, IrType type, Optional<IrType> expectedType,
                          boolean polyExpression, List<ExpressionTypePlan> operands,
                          Optional<InvocationPlan> invocation) {
    ExpressionTypePlan {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(type, "type");
        expectedType = expectedType == null ? Optional.empty() : expectedType;
        operands = operands == null ? List.of() : List.copyOf(operands);
        invocation = invocation == null ? Optional.empty() : invocation;
    }

    static ExpressionTypePlan simple(Expression expression, IrType type,
                                     Optional<IrType> expectedType) {
        return new ExpressionTypePlan(expression, type, expectedType,
                false, List.of(), Optional.empty());
    }
}
