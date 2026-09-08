// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;
import java.util.List;

/** Narrow native descriptor operations. Validation and stream state live in source. */
public record IrStreamInstruction(IrValueReference result, Operation operation,
                                  List<IrOperand> arguments, SourceSpan sourceSpan)
        implements IrInstruction {
    public enum Operation {
        OPEN("openValue", "open", IrType.I32,
                List.of(IrType.reference("ironwood.lang.String"), IrType.I32)),
        READ_BYTE("readByte", "read_byte", IrType.I32, List.of(IrType.I32)),
        READ_BYTES("readBytes", "read_bytes", IrType.I32,
                List.of(IrType.I32, IrType.array(IrType.I8), IrType.I32, IrType.I32)),
        WRITE_BYTE("writeByte", "write_byte", IrType.I32, List.of(IrType.I32, IrType.I32)),
        WRITE_BYTES("writeBytes", "write_bytes", IrType.I32,
                List.of(IrType.I32, IrType.array(IrType.I8), IrType.I32, IrType.I32)),
        AVAILABLE("available", "available", IrType.I32, List.of(IrType.I32)),
        POSITION("positionValue", "position", IrType.I64, List.of(IrType.I32)),
        SEEK("seekValue", "seek", IrType.I64, List.of(IrType.I32, IrType.I64)),
        LENGTH("lengthValue", "length", IrType.I64, List.of(IrType.I32)),
        SET_LENGTH("setLengthValue", "set_length", IrType.I32, List.of(IrType.I32, IrType.I64)),
        CLOSE("close", "close", IrType.I32, List.of(IrType.I32)),
        PRINT_BYTE("printByte", "print_byte", IrType.I32,
                List.of(IrType.reference("ironwood.io.PrintStream"), IrType.I32)),
        PRINT_BYTES("printBytes", "print_bytes", IrType.I32,
                List.of(IrType.reference("ironwood.io.PrintStream"), IrType.array(IrType.I8), IrType.I32, IrType.I32));

        private final String sourceName;
        private final String runtimeName;
        private final IrType resultType;
        private final List<IrType> parameterTypes;
        Operation(String sourceName, String runtimeName, IrType resultType,
                  List<IrType> parameterTypes) {
            this.sourceName = sourceName;
            this.runtimeName = runtimeName;
            this.resultType = resultType;
            this.parameterTypes = parameterTypes;
        }
        public String sourceName() { return sourceName; }
        public String runtimeName() { return "ironwood_stream_" + runtimeName; }
        public IrType resultType() { return resultType; }
        public List<IrType> parameterTypes() { return parameterTypes; }
    }

    public IrStreamInstruction {
        arguments = List.copyOf(arguments);
        if (!result.type().equals(operation.resultType())
                || !arguments.stream().map(IrOperand::type).toList().equals(operation.parameterTypes())) {
            throw new IllegalArgumentException("stream operation signature mismatch");
        }
    }
}
