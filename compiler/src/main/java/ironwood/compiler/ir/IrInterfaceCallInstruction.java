// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record IrInterfaceCallInstruction(Optional<IrValueReference> result, String interfaceName,
                                         IrDispatchSlot slot, IrType returnType,
                                         List<IrOperand> arguments,
                                         Map<String, IrType> specializationArguments,
                                         SourceSpan sourceSpan) implements IrInstruction {
    public IrInterfaceCallInstruction {
        result = result == null ? Optional.empty() : result;
        arguments = List.copyOf(arguments);
        specializationArguments = specializationArguments == null
                ? Map.of() : Map.copyOf(specializationArguments);
    }

    public IrInterfaceCallInstruction(Optional<IrValueReference> result, String interfaceName,
                                      IrDispatchSlot slot, IrType returnType,
                                      List<IrOperand> arguments, SourceSpan sourceSpan) {
        this(result, interfaceName, slot, returnType, arguments, Map.of(), sourceSpan);
    }
}
