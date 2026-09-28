// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Protected conversion of adapter-owned primitive input state, shared by aliases. */
public record IrBridgeArrayCopyInstruction(IrValueReference result, IrOperand stateAddress,
                                            SourceSpan sourceSpan) implements IrInstruction {
    public IrBridgeArrayCopyInstruction {
        if (!result.type().isArray() || !result.type().elementType().isPrimitive()
                || !stateAddress.type().equals(IrType.I64)) {
            throw new IllegalArgumentException("bridge array copy requires primitive array and private state address");
        }
    }
}
