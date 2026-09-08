// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record IrBasicBlock(String label, List<IrInstruction> instructions,
                           IrTerminator terminator, SourceSpan sourceSpan) {
    public IrBasicBlock {
        instructions = List.copyOf(instructions);
    }
}
