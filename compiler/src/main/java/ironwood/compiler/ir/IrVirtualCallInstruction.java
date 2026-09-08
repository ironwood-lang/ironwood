// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record IrVirtualCallInstruction(Optional<IrValueReference> result, IrDispatchSlot slot,
                                       IrType returnType, List<IrOperand> arguments,
                                       Map<String, IrType> specializationArguments,
                                       SourceSpan sourceSpan) implements IrInstruction {
    public IrVirtualCallInstruction {
        result = result == null ? Optional.empty() : result;
        arguments = List.copyOf(arguments);
        specializationArguments = specializationArguments == null
                ? Map.of() : Map.copyOf(specializationArguments);
    }

    public IrVirtualCallInstruction(Optional<IrValueReference> result, IrDispatchSlot slot,
                                    IrType returnType, List<IrOperand> arguments,
                                    SourceSpan sourceSpan) {
        this(result, slot, returnType, arguments, Map.of(), sourceSpan);
    }
}
