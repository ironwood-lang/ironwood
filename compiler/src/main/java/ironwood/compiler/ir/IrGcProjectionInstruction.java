// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Development lowering of a managed field address, retaining its typed base. */
public record IrGcProjectionInstruction(IrValueReference result, IrOperand base, IrField field,
                                         SourceSpan sourceSpan) implements IrInstruction {}
