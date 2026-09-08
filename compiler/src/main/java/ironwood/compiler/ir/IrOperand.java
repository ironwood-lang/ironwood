// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public sealed interface IrOperand permits IrConstant, IrEnumConstant, IrImmortalObject, IrNull,
        IrStringConstant, IrValueReference {
    IrType type();

    SourceSpan sourceSpan();
}
