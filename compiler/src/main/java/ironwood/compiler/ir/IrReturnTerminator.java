// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record IrReturnTerminator(Optional<IrOperand> value,
                                 SourceSpan sourceSpan) implements IrTerminator {
    public IrReturnTerminator {
        value = value == null ? Optional.empty() : value;
    }
}
