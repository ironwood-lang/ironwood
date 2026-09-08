// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public sealed interface IrTerminator permits IrBranch, IrInvokeTerminator, IrJump,
        IrReturnTerminator, IrSwitchTerminator, IrThrowTerminator, IrUnreachable {
    SourceSpan sourceSpan();
}
