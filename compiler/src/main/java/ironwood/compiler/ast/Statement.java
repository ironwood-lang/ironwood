// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public sealed interface Statement permits AssignmentStatement, Block, BreakStatement,
        ContinueStatement, DoWhileStatement, EmptyStatement, EnhancedForStatement,
        EnumConstantInitialization, ExpressionStatement, ForStatement, FreeStatement,
        IfStatement, LabeledStatement, LocalClassDeclaration, LocalVariableDeclaration,
        ModernSwitchStatement, ReturnStatement, SuperConstructorInvocation, SwitchStatement,
        ThisConstructorInvocation, ThrowStatement, TryStatement, WhileStatement, YieldStatement {
    SourceSpan span();
}
