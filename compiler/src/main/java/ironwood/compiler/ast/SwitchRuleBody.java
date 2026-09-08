// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public sealed interface SwitchRuleBody permits SwitchRuleBlock, SwitchRuleExpression,
        SwitchRuleThrow {
    SourceSpan span();
}
