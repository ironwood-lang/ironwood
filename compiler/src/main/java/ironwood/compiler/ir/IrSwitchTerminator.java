// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record IrSwitchTerminator(IrOperand selector, List<IrSwitchCase> cases,
                                 String defaultTarget,
                                 SourceSpan sourceSpan) implements IrTerminator {
    public IrSwitchTerminator {
        cases = List.copyOf(cases);
        if (!selector.type().isIntegral() || selector.type().equals(IrType.I64)) {
            throw new IllegalArgumentException(
                    "switch selector must be byte, short, char, or int");
        }
        if (cases.stream().anyMatch(branch -> !branch.value().type().equals(selector.type()))) {
            throw new IllegalArgumentException(
                    "switch case constants must have the selector type");
        }
    }
}
