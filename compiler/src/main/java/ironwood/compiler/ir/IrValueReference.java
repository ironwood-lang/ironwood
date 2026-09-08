// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrValueReference(int id, IrType type, SourceSpan sourceSpan) implements IrOperand {
}
