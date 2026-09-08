// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrField(String ownerClass, String name, IrType type, int layoutIndex,
                      SourceSpan sourceSpan) {
}
