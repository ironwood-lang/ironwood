// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import ironwood.compiler.ir.IrByteViewInstruction;
import ironwood.compiler.ir.IrOperand;
import java.util.function.Function;

/** Bounded borrowed descriptors have their own layout, independent of native objects. */
final class LlvmByteViewEmitter {
    private LlvmByteViewEmitter() {}

    // The adapter initializes these fields once for the descriptor's lifetime.
    // Payload ranges may overlap; only descriptor fields are invariant.
    private static final String IMMUTABLE = ", !invariant.load !{}";

    static void emit(StringBuilder text, IrByteViewInstruction instruction,
                     Function<IrOperand, String> operand, Function<String, String> scratch) {
        String field = scratch.apply("view.field");
        int index = switch (instruction.operation()) {
            case LENGTH -> 1; case READ_ONLY -> 2; case READ, WRITE -> 0;
        };
        text.append(field).append(" = getelementptr inbounds %\"ironwood.byteview\", ptr ")
                .append(operand.apply(instruction.view())).append(", i32 0, i32 ").append(index).append("\n  ");
        switch (instruction.operation()) {
            case LENGTH -> text.append(operand.apply(instruction.result().orElseThrow()))
                    .append(" = load i32, ptr ").append(field).append(IMMUTABLE);
            case READ_ONLY -> {
                String permission = scratch.apply("view.permission");
                text.append(permission).append(" = load i8, ptr ").append(field).append(IMMUTABLE).append("\n  ")
                        .append(operand.apply(instruction.result().orElseThrow()))
                        .append(" = icmp ne i8 ").append(permission).append(", 0");
            }
            case READ, WRITE -> {
                String data = scratch.apply("view.data"), element = scratch.apply("view.element");
                text.append(data).append(" = load ptr, ptr ").append(field).append(IMMUTABLE).append("\n  ")
                        .append(element).append(" = getelementptr inbounds i8, ptr ").append(data)
                        .append(", i32 ").append(operand.apply(instruction.arguments().getFirst())).append("\n  ");
                if (instruction.operation() == IrByteViewInstruction.Operation.READ) {
                    text.append(operand.apply(instruction.result().orElseThrow())).append(" = load i8, ptr ").append(element);
                } else text.append("store i8 ").append(operand.apply(instruction.arguments().get(1))).append(", ptr ").append(element);
            }
        }
    }
}
