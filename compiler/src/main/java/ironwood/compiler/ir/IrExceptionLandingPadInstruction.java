// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrExceptionLandingPadInstruction(IrValueReference exceptionHandle,
                                               IrValueReference exceptionObject,
                                               SourceSpan sourceSpan) implements IrInstruction {
}
