// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Exact IEEE-754 payload reinterpretation between primitive floating and integer types. */
public record IrFloatingBitsInstruction(IrValueReference result, Operation operation,
                                        IrOperand value,
                                        SourceSpan sourceSpan) implements IrInstruction {
    public IrFloatingBitsInstruction {
        if (!result.type().equals(operation.resultType())
                || !value.type().equals(operation.valueType())) {
            throw new IllegalArgumentException("floating bit conversion types do not match operation");
        }
    }

    public enum Operation {
        FLOAT_TO_RAW_INT(IrType.I32, IrType.F32),
        INT_TO_FLOAT(IrType.F32, IrType.I32),
        DOUBLE_TO_RAW_LONG(IrType.I64, IrType.F64),
        LONG_TO_DOUBLE(IrType.F64, IrType.I64);

        private final IrType resultType;
        private final IrType valueType;

        Operation(IrType resultType, IrType valueType) {
            this.resultType = resultType;
            this.valueType = valueType;
        }

        public IrType resultType() {
            return this.resultType;
        }

        public IrType valueType() {
            return this.valueType;
        }
    }
}
