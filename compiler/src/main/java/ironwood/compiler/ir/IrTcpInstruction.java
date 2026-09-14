// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;
import java.util.List;

/** Allocation-free TCP attempts with captured errors, distinct from any future TLS operation. */
public record IrTcpInstruction(IrValueReference result, Operation operation,
                               List<IrOperand> arguments, List<IrField> outputFields,
                               SourceSpan sourceSpan) implements IrInstruction {
    private static final IrType BYTES = IrType.array(IrType.I8);
    private static final IrType DESCRIPTOR = IrType.reference("ironwood.net.SocketDescriptor");

    public enum Operation {
        CREATE("create", List.of(IrType.I32)),
        BIND("bind", addressParameters()),
        CONNECT("connect", addressParameters()),
        LISTEN("listen", List.of(IrType.I32, IrType.I32)),
        ACCEPT("accept", List.of(IrType.I32)),
        COMPLETE_CONNECT("completeConnect", List.of(IrType.I32)),
        READ_BYTE("readByte", List.of(IrType.I32)),
        TRY_READ_BYTE("tryReadByte", List.of(IrType.I32)),
        READ_BYTES("readBytes", List.of(IrType.I32, BYTES, IrType.I32, IrType.I32)),
        TRY_READ_BYTES("tryReadBytes", List.of(IrType.I32, BYTES, IrType.I32, IrType.I32)),
        WRITE_BYTE("writeByte", List.of(IrType.I32, IrType.I32)),
        WRITE_BYTES("writeBytes", List.of(IrType.I32, BYTES, IrType.I32, IrType.I32)),
        AVAILABLE("available", List.of(IrType.I32)),
        SHUTDOWN("shutdown", List.of(IrType.I32, IrType.I32)),
        CLOSE("close", List.of(IrType.I32)),
        WAIT("waitReady", List.of(IrType.I32, IrType.I1, IrType.I64)),
        BLOCKING("blocking", List.of(IrType.I32, IrType.I1)),
        RESTORE_FLAGS("restoreFlags", List.of(IrType.I32, IrType.I32)),
        GET_BOOLEAN("getBoolean", List.of(IrType.I32, IrType.I32)),
        SET_BOOLEAN("setBoolean", List.of(IrType.I32, IrType.I32, IrType.I1)),
        GET_INTEGER("getInteger", List.of(IrType.I32, IrType.I32)),
        SET_INTEGER("setInteger", List.of(IrType.I32, IrType.I32, IrType.I32)),
        ENDPOINT("endpoint", List.of(IrType.I32, IrType.I1, DESCRIPTOR)),
        ERROR_KIND("errorKind", List.of(IrType.I32));

        private final String sourceName;
        private final List<IrType> parameters;
        Operation(String sourceName, List<IrType> parameters) {
            this.sourceName = sourceName;
            this.parameters = parameters;
        }
        public String sourceName() { return sourceName; }
        public String runtimeName() { return "ironwood_tcp_" + name().toLowerCase(java.util.Locale.ROOT); }
        public List<IrType> parameterTypes() { return parameters; }
        public IrType resultType() { return this == ERROR_KIND ? IrType.I32 : IrType.I64; }
        private static List<IrType> addressParameters() {
            return java.util.Collections.nCopies(8, IrType.I32);
        }
    }

    public IrTcpInstruction {
        arguments = List.copyOf(arguments);
        outputFields = List.copyOf(outputFields);
        if (!result.type().equals(operation.resultType())
                || !arguments.stream().map(IrOperand::type).toList().equals(operation.parameterTypes())
                || (operation == Operation.ENDPOINT ? outputFields.size() != 6
                        || outputFields.stream().anyMatch(field -> !field.type().equals(IrType.I32)
                                || !field.ownerClass().equals(DESCRIPTOR.referenceName())) : !outputFields.isEmpty())) {
            throw new IllegalArgumentException("TCP operation signature mismatch");
        }
    }
}
