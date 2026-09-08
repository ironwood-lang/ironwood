// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Copies a bounded byte-array prefix into a fresh String, replacing malformed UTF-8. */
public record IrStringFromUtf8Instruction(IrValueReference result, IrOperand bytes,
                                           IrOperand length, SourceSpan sourceSpan)
        implements IrInstruction {
    public IrStringFromUtf8Instruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !bytes.type().equals(IrType.array(IrType.I8))
                || !length.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("UTF-8 snapshot requires byte[] and int length");
        }
    }
}
