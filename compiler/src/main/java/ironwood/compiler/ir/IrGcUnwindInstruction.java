// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** GC4 native cleanup pad: retain the wrapper until native resume/delete.
 * begin defines a native unwind handle; resume consumes it and cannot return.
 * This is internal development IR, never a source lifetime exemption.
 */
public record IrGcUnwindInstruction(IrValueReference handle, boolean begin, SourceSpan sourceSpan) implements IrInstruction {
    public IrGcUnwindInstruction {
        if (!handle.type().equals(IrType.EXCEPTION)) throw new IllegalArgumentException("native unwind handle required");
    }
}
