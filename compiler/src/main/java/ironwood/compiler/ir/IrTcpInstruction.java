// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;
import java.util.List;

/** TCP attempts and OS address services, distinct from any future TLS operation. */
public record IrTcpInstruction(IrValueReference result, Operation operation,
                               List<IrOperand> arguments, List<IrField> outputFields,
                               SourceSpan sourceSpan) implements IrInstruction {
    private static final IrType BYTES = IrType.array(IrType.I8);
    private static final IrType DESCRIPTOR = IrType.reference("ironwood.net.SocketDescriptor");

    private static final IrType RESOLVER = IrType.reference("ironwood.net.ResolverQuery");

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
        URGENT("urgent", List.of(IrType.I32, IrType.I32)),
        GET_TRAFFIC_CLASS("getTrafficClass", List.of(IrType.I32, IrType.I32)),
        SET_TRAFFIC_CLASS("setTrafficClass", List.of(IrType.I32, IrType.I32, IrType.I32)),
        REUSE_PORT_SUPPORTED("reusePortSupported", List.of()),
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
        RESOLVE_START("resolveStart", List.of(BYTES, RESOLVER)),
        RESOLVE_ADDRESS("resolveAddress", List.of(IrType.I64, IrType.I32, RESOLVER)),
        RESOLVE_RELEASE("resolveRelease", List.of(IrType.I64)),
        REVERSE_NAME("reverseName", List.of(IrType.I32, IrType.I32, IrType.I32, IrType.I32,
                IrType.I32, IrType.I32, BYTES)),
        LOCAL_NAME("localName", List.of(BYTES)),
        SCOPE_ID("scopeId", List.of(BYTES, IrType.I32)),
        PREFERRED_FAMILY("preferredFamily", List.of()),
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
        public List<String> outputNames() {
            return switch (this) {
                case ENDPOINT -> List.of("queryFamily", "query0", "query1", "query2", "query3", "queryPort", "queryScope");
                case RESOLVE_START -> List.of("handle", "count");
                case RESOLVE_ADDRESS -> List.of("family", "first", "second", "third", "fourth", "scope", "cursor", "preferred");
                default -> List.of();
            };
        }
        public IrType outputType(int index) {
            return (this == RESOLVE_START && index == 0 || this == RESOLVE_ADDRESS && index == 6)
                    ? IrType.I64 : IrType.I32;
        }
        private static List<IrType> addressParameters() {
            return java.util.Collections.nCopies(9, IrType.I32);
        }
    }

    public IrTcpInstruction {
        arguments = List.copyOf(arguments);
        outputFields = List.copyOf(outputFields);
        if (!result.type().equals(operation.resultType())
                || !arguments.stream().map(IrOperand::type).toList().equals(operation.parameterTypes())
                || outputFields.size() != operation.outputNames().size()) {
            throw new IllegalArgumentException("TCP operation signature mismatch");
        }
        for (int index = 0; index < outputFields.size(); index++) {
            IrField field = outputFields.get(index);
            IrType owner = operation.parameterTypes().getLast();
            if (!field.type().equals(operation.outputType(index))
                    || !field.ownerClass().equals(owner.referenceName())
                    || !field.name().equals(operation.outputNames().get(index))) {
                throw new IllegalArgumentException("TCP output field mismatch");
            }
        }
    }
}
