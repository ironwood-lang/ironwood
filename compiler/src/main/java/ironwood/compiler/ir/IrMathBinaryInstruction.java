// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrMathBinaryInstruction(IrValueReference result, Operation operation,
                                      IrOperand left, IrOperand right,
                                      SourceSpan sourceSpan) implements IrInstruction {
    public IrMathBinaryInstruction {
        if (!result.type().equals(IrType.F64)
                || !left.type().equals(IrType.F64) || !right.type().equals(IrType.F64)) {
            throw new IllegalArgumentException("native Math binary operation requires doubles");
        }
    }

    public enum Operation {
        ATAN2("atan2"),
        POW("llvm.pow.f64"),
        HYPOT("hypot");

        private final String functionName;

        Operation(String functionName) {
            this.functionName = functionName;
        }

        public String functionName() {
            return this.functionName;
        }
    }
}
