// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrConstant;
import ironwood.compiler.ir.IrNull;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrStringConstant;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceSpan;

record ConstantValue(IrType type, Number value, IrStringConstant stringValue) {
    ConstantValue(IrType type, Number value) {
        this(type, value, null);
    }

    static ConstantValue nullValue() {
        return new ConstantValue(IrType.NULL, null, null);
    }

    static ConstantValue stringValue(IrStringConstant value) {
        return new ConstantValue(IrType.reference("ironwood.lang.String"), null, value);
    }

    boolean isNull() {
        return type.equals(IrType.NULL);
    }

    IrOperand operand(IrType declaredType, SourceSpan span) {
        if (isNull()) {
            return new IrNull(declaredType, span);
        }
        if (stringValue != null) {
            return stringValue;
        }
        return new IrConstant(declaredType, value, span);
    }
}
