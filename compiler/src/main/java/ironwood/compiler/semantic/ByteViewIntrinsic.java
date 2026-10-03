// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrByteViewInstruction;
import ironwood.compiler.source.SourceFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Exact bundled declaration authority survives source/class/archive reconstruction. */
public final class ByteViewIntrinsic {
    private ByteViewIntrinsic() {}
    public static final String SOURCE_SHA256 = "e88c4dbc78e057285069a9fd4b256ac79f62590de9ad3ef0453329ed43e3a850";

    public static boolean trusted(SourceFile source) {
        try {
            return SOURCE_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.content().getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static Optional<IrByteViewInstruction.Operation> operation(CallableSymbol method, SourceFile source) {
        if (!method.ownerType().equals(IrByteViewInstruction.TYPE.referenceName()) || method.isStatic()
                || !trusted(source)) return Optional.empty();
        var operation = switch (method.sourceName()) {
            case "length" -> IrByteViewInstruction.Operation.LENGTH;
            case "isReadOnly" -> IrByteViewInstruction.Operation.READ_ONLY;
            case "readByte" -> IrByteViewInstruction.Operation.READ;
            case "writeByte" -> IrByteViewInstruction.Operation.WRITE;
            default -> null;
        };
        return operation != null && operation.resultType().equals(method.returnType())
                && operation.parameterTypes().equals(method.parameterTypes()) ? Optional.of(operation) : Optional.empty();
    }
}
