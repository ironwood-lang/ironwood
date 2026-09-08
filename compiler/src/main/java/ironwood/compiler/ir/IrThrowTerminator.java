// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record IrThrowTerminator(IrOperand exception, String normalTarget,
                                Optional<String> unwindTarget,
                                SourceSpan sourceSpan) implements IrTerminator {
    public IrThrowTerminator {
        unwindTarget = unwindTarget == null ? Optional.empty() : unwindTarget;
    }
}
