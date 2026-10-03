// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;
import java.util.List;

/**
 * Append one proved pure event to private invocation scratch using the original
 * loop index. Returns whether a full or final chunk needs delivery.
 * No call, allocation or exception occurs here. A separate foreign instruction
 * delivers the chunk and retains all its conservative callback effects.
 * Only post-admission bridge scheduling may construct this operation: the
 * context owns capacity * arity writable longs, 0 <= index < count, and no
 * buffer address escapes this invocation. The proof supplies the loop index;
 * callback payload values are not trusted as transport positions.
 */
public record IrBridgeBatchAppendInstruction(IrValueReference result, IrOperand context,
                                             IrOperand index, IrOperand count,
                                             List<IrOperand> arguments, SourceSpan sourceSpan) implements IrInstruction {
    public static final int CAPACITY = 1024;
    public static final int DATA_OFFSET = 24;

    public IrBridgeBatchAppendInstruction {
        arguments = List.copyOf(arguments);
        if (!result.type().equals(IrType.I1) || !context.type().equals(IrType.I64)
                || !index.type().equals(IrType.I32) || !count.type().equals(IrType.I32)
                || arguments.isEmpty() || arguments.size() > 4
                || arguments.stream().anyMatch(value -> !value.type().equals(IrType.I64))) {
            throw new IllegalArgumentException("batch append requires a boolean result, context and one to four long values");
        }
    }
}
