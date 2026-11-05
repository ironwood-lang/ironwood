// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Explicit collection, including catchable preparation failure. Native admission is separate. */
public record IrGcCollectInstruction(SourceSpan sourceSpan) implements IrInstruction {}
