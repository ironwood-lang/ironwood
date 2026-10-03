// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Canonical typed proxy body, shared with the exact receiver-publication proof. */
public final class BridgeCallbackAbi {
    private BridgeCallbackAbi() {}

    /** References remain visible to analysis; this does not authorize their transport. */
    public static IrType carrier(IrType type) {
        return type.equals(IrType.I1) || type.isIntegral() ? IrType.I64 : type;
    }

    public static List<IrBasicBlock> body(IrFunction function, IrField field, String symbol) {
        var span = function.sourceSpan();
        int next = function.parameters().size();
        var handle = new IrValueReference(next++, IrType.I64, span);
        var blocks = new ArrayList<IrBasicBlock>();
        var instructions = new ArrayList<IrInstruction>();
        instructions.add(new IrFieldLoadInstruction(handle, function.parameters().getFirst().value(), field, span));
        var arguments = new ArrayList<IrOperand>();
        arguments.add(handle);
        String label = "entry";
        for (int index = 1; index < function.parameters().size(); index++) {
            var value = function.parameters().get(index).value();
            var type = carrier(value.type());
            if (value.type().equals(type)) {
                arguments.add(value);
                continue;
            }
            var converted = new IrValueReference(next++, type, span);
            if (value.type().equals(IrType.I1)) {
                // Ordinary typed control flow, optimized to zero extension by LLVM.
                String yes = "boolean_true_" + index, no = "boolean_false_" + index, join = "boolean_join_" + index;
                blocks.add(new IrBasicBlock(label, instructions, new IrBranch(value, yes, no, span), span));
                blocks.add(new IrBasicBlock(yes, List.of(), new IrJump(join, span), span));
                blocks.add(new IrBasicBlock(no, List.of(), new IrJump(join, span), span));
                instructions = new ArrayList<>();
                instructions.add(new IrPhiInstruction(converted, List.of(
                        new IrPhiIncoming(yes, new IrConstant(IrType.I64, 1L, span)),
                        new IrPhiIncoming(no, new IrConstant(IrType.I64, 0L, span))), span));
                label = join;
            } else {
                instructions.add(new IrNumericConversionInstruction(converted, value, span));
            }
            arguments.add(converted);
        }
        var resultType = carrier(function.returnType());
        var result = resultType.equals(IrType.VOID) ? Optional.<IrValueReference>empty()
                : Optional.of(new IrValueReference(next++, resultType, span));
        instructions.add(new IrForeignCallInstruction(result, symbol, resultType, arguments, span));
        Optional<IrOperand> returned = result.map(value -> value);
        if (!resultType.equals(function.returnType())) {
            var converted = new IrValueReference(next, function.returnType(), span);
            if (function.returnType().equals(IrType.I1)) {
                instructions.add(new IrBinaryInstruction(converted, IrBinaryOperator.NOT_EQUAL,
                        result.orElseThrow(), new IrConstant(IrType.I64, 0L, span), span));
            } else {
                instructions.add(new IrNumericConversionInstruction(converted, result.orElseThrow(), span));
            }
            returned = Optional.of(converted);
        }
        blocks.add(new IrBasicBlock(label, instructions, new IrReturnTerminator(returned, span), span));
        return List.copyOf(blocks);
    }
}
