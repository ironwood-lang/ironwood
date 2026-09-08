// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrStringConstant(int id, String value, int utf16Length, int utf8Length,
                               SourceSpan sourceSpan) implements IrOperand {
    private static final IrType STRING_TYPE = IrType.reference("ironwood.lang.String");

    @Override
    public IrType type() {
        return STRING_TYPE;
    }
}
