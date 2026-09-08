// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrMathUnaryInstruction(IrValueReference result, Operation operation,
                                     IrOperand value,
                                     SourceSpan sourceSpan) implements IrInstruction {
    public IrMathUnaryInstruction {
        if (!result.type().equals(IrType.F64) || !value.type().equals(IrType.F64)) {
            throw new IllegalArgumentException("native Math unary operation requires double");
        }
    }

    public enum Operation {
        SIN("llvm.sin.f64"),
        COS("llvm.cos.f64"),
        TAN("tan"),
        EXP("llvm.exp.f64"),
        LOG("llvm.log.f64"),
        LOG10("llvm.log10.f64"),
        SQRT("llvm.sqrt.f64"),
        CBRT("cbrt"),
        RINT("llvm.rint.f64");

        private final String functionName;

        Operation(String functionName) {
            this.functionName = functionName;
        }

        public String functionName() {
            return this.functionName;
        }
    }
}
