// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;
import java.util.List;
import java.util.Optional;

/** Access to a lifetime-immutable descriptor and potentially overlapping mutable payload.
 * Source get/put bodies establish null, bounds and permissions before byte access.
 */
public record IrByteViewInstruction(Optional<IrValueReference> result, Operation operation,
                                    IrOperand view, List<IrOperand> arguments,
                                    SourceSpan sourceSpan) implements IrInstruction {
    public static final IrType TYPE = IrType.reference("ironwood.bridge.ByteView");

    public enum Operation {
        LENGTH(IrType.I32, List.of()), READ_ONLY(IrType.I1, List.of()),
        READ(IrType.I8, List.of(IrType.I32)), WRITE(IrType.VOID, List.of(IrType.I32, IrType.I8));

        private final IrType result;
        private final List<IrType> parameters;
        Operation(IrType result, List<IrType> parameters) { this.result = result; this.parameters = parameters; }
        public IrType resultType() { return result; }
        public List<IrType> parameterTypes() { return parameters; }
    }

    public IrByteViewInstruction {
        arguments = List.copyOf(arguments);
        if (!view.type().equals(TYPE)
                || !result.map(IrValueReference::type).orElse(IrType.VOID).equals(operation.resultType())
                || !arguments.stream().map(IrOperand::type).toList().equals(operation.parameterTypes())) {
            throw new IllegalArgumentException("invalid bounded byte-view operation");
        }
    }
}
