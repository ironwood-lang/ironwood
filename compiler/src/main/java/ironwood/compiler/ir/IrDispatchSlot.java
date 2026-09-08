// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record IrDispatchSlot(int index, String key, String methodName, IrType returnType,
                             List<IrType> parameterTypes, SourceSpan sourceSpan) {
    public IrDispatchSlot {
        parameterTypes = List.copyOf(parameterTypes);
    }
}
