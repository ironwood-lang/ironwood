// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public sealed interface Expression permits ArrayAccessExpression, ArrayCreationExpression,
        ArrayInitializerExpression,
        AssignmentExpression, BinaryExpression, BooleanLiteralExpression, CallExpression,
        CastExpression, CharacterLiteralExpression, ConditionalExpression, FieldAccessExpression,
        FloatingLiteralExpression, IntegerLiteralExpression, InterfaceSuperExpression,
        NameExpression, InstanceOfExpression, NewExpression, NullLiteralExpression,
        QualifiedSuperConstructorExpression, QualifiedThisExpression,
        StringLiteralExpression, SuperExpression, SwitchExpression, ThisExpression,
        UnaryExpression, UpdateExpression {
    SourceSpan span();
}
