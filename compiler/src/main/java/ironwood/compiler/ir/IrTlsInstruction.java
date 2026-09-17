// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;
import java.util.List;

/** Optional TLS operations. All managed arguments are call-scoped borrows. */
public record IrTlsInstruction(IrValueReference result, Operation operation,
                               List<IrOperand> arguments, SourceSpan sourceSpan) implements IrInstruction {
    private static final IrType BYTES = IrType.array(IrType.I8);

    public enum Operation {
        CREATE("create", List.of(BYTES)),
        CONFIGURE("configure", List.of(IrType.I64, BYTES, BYTES)),
        ATTACH("attach", List.of(IrType.I64, IrType.I32)),
        HANDSHAKE("handshake", List.of(IrType.I64)),
        READ_BYTE("readByte", List.of(IrType.I64)),
        READ_BYTES("readBytes", List.of(IrType.I64, BYTES, IrType.I32, IrType.I32)),
        WRITE_BYTE("writeByte", List.of(IrType.I64, IrType.I32)),
        WRITE_BYTES("writeBytes", List.of(IrType.I64, BYTES, IrType.I32, IrType.I32)),
        AVAILABLE("available", List.of(IrType.I64)),
        WAIT("waitReady", List.of(IrType.I64, IrType.I1, IrType.I64)),
        CLOSE("close", List.of(IrType.I64, IrType.I1));

        private final String sourceName;
        private final List<IrType> parameters;
        Operation(String sourceName, List<IrType> parameters) {
            this.sourceName = sourceName;
            this.parameters = parameters;
        }
        public String sourceName() { return sourceName; }
        public List<IrType> parameterTypes() { return parameters; }
        public List<IrType> sourceParameterTypes() {
            return this == ATTACH ? List.of(IrType.I64, IrType.reference("ironwood.net.SocketDescriptor"))
                    : parameters;
        }
        public String runtimeName() { return "ironwood_tls_" + name().toLowerCase(java.util.Locale.ROOT); }
    }

    public IrTlsInstruction {
        arguments = List.copyOf(arguments);
        if (!result.type().equals(IrType.I64)
                || !arguments.stream().map(IrOperand::type).toList().equals(operation.parameterTypes())) {
            throw new IllegalArgumentException("TLS operation signature mismatch");
        }
    }
}
