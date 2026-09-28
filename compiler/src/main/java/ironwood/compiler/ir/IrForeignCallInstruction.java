// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Compiler-owned foreign call in a hidden bridge proxy. The target is outside
 * the native closed world: it may publish inputs, allocate, throw and reenter.
 * No instance of this operation grants a borrowing or non-reclamation proof.
 */
public record IrForeignCallInstruction(Optional<IrValueReference> result, String targetLinkageName,
                                       IrType returnType, List<IrOperand> arguments,
                                       SourceSpan sourceSpan) implements IrInstruction {
    public IrForeignCallInstruction {
        result = Objects.requireNonNull(result);
        Objects.requireNonNull(returnType);
        Objects.requireNonNull(sourceSpan);
        arguments = List.copyOf(arguments);
        if (targetLinkageName == null || !targetLinkageName.matches("ironwood_bridge_callback_[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("foreign call requires a compiler-owned callback target");
        }
        if (result.isPresent() && (returnType.equals(IrType.VOID)
                || !result.orElseThrow().type().equals(returnType))) {
            throw new IllegalArgumentException("foreign call result must match its declared type");
        }
        if (arguments.stream().anyMatch(argument -> argument.type().equals(IrType.VOID))) {
            throw new IllegalArgumentException("foreign call arguments cannot be void");
        }
    }
}
