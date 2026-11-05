// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Development-only dereference of a validated physical field projection. */
public record IrGcReadProjectionInstruction(IrValueReference result, IrValueReference address,
                                             SourceSpan sourceSpan) implements IrInstruction {}
