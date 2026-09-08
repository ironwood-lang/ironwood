// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record IrPhiInstruction(IrValueReference result, List<IrPhiIncoming> incoming,
                               SourceSpan sourceSpan) implements IrInstruction {
    public IrPhiInstruction {
        incoming = List.copyOf(incoming);
    }
}
