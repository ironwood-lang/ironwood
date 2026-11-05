// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Isolated scalar-native-exception harness, not Ironwood Throwable transport.
 * The fixed C++ helper throws/catches only an int and cannot collect/callback or
 * retain a managed reference. CATCH must be first in its invoke unwind block.
 */
public record IrGcTestExceptionInstruction(Operation operation, SourceSpan sourceSpan) implements IrInstruction {
    public enum Operation { RAISE_SCALAR, CATCH_SCALAR }
}
