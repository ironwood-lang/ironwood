// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record IrInstanceOfInstruction(IrValueReference result, IrOperand value,
                                      String targetTypeName, int targetTypeId,
                                      Optional<IrType> exactTargetType,
                                      SourceSpan sourceSpan) implements IrInstruction {
    public IrInstanceOfInstruction {
        exactTargetType = exactTargetType == null ? Optional.empty() : exactTargetType;
    }

    public IrInstanceOfInstruction(IrValueReference result, IrOperand value,
                                   String targetTypeName, int targetTypeId,
                                   SourceSpan sourceSpan) {
        this(result, value, targetTypeName, targetTypeId, Optional.empty(), sourceSpan);
    }
}
