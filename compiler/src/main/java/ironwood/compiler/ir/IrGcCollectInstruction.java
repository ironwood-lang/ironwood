// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Compiler-owned explicit collection. Only admitted development closures may lower it. */
public record IrGcCollectInstruction(SourceSpan sourceSpan) implements IrInstruction {}
