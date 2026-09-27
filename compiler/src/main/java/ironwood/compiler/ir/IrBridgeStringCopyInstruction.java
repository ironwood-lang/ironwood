// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Protected compiler-only copy of a JNI UTF-16 buffer; length -1 represents null. */
public record IrBridgeStringCopyInstruction(IrValueReference result, IrOperand address,
                                             IrOperand length, SourceSpan sourceSpan) implements IrInstruction {
    public IrBridgeStringCopyInstruction {
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || !address.type().equals(IrType.I64) || !length.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("bridge String copy requires an address and length");
        }
    }
}
