// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record IrCallInstruction(Optional<IrValueReference> result, String targetLinkageName,
                                IrType returnType, List<IrOperand> arguments,
                                IrCallKind callKind, Optional<String> devirtualizedFrom,
                                Map<String, IrType> specializationArguments,
                                SourceSpan sourceSpan) implements IrInstruction {
    public IrCallInstruction {
        result = result == null ? Optional.empty() : result;
        arguments = List.copyOf(arguments);
        devirtualizedFrom = devirtualizedFrom == null ? Optional.empty() : devirtualizedFrom;
        specializationArguments = specializationArguments == null
                ? Map.of() : Map.copyOf(specializationArguments);
    }

    public IrCallInstruction(Optional<IrValueReference> result, String targetLinkageName,
                             IrType returnType, List<IrOperand> arguments,
                             IrCallKind callKind, Optional<String> devirtualizedFrom,
                             SourceSpan sourceSpan) {
        this(result, targetLinkageName, returnType, arguments, callKind, devirtualizedFrom,
                Map.of(), sourceSpan);
    }

    public IrCallInstruction(Optional<IrValueReference> result, String targetLinkageName,
                             IrType returnType, List<IrOperand> arguments,
                             SourceSpan sourceSpan) {
        this(result, targetLinkageName, returnType, arguments, IrCallKind.DIRECT,
                Optional.empty(), Map.of(), sourceSpan);
    }
}
