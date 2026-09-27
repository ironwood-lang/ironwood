// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;

/** Shared D194 active-use blocks; the caller supplies proved exact token alternatives. */
final class BridgeEnumConversion {
    private BridgeEnumConversion() {}

    static int append(List<IrBasicBlock> blocks, BridgeEnumInvocation.Parameter parameter,
            IrValueReference token, IrValueReference result, int next, String label, String continuation,
            String failure, String invalid, SourceSpan span) {
        List<IrSwitchCase> cases = new ArrayList<>();
        List<IrPhiIncoming> incoming = new ArrayList<>();
        if (parameter.nullable()) {
            cases.add(new IrSwitchCase(new IrConstant(IrType.I32, -1, span), label + ".null", span));
            blocks.add(new IrBasicBlock(label + ".null", List.of(), new IrJump(label + ".join", span), span));
            incoming.add(new IrPhiIncoming(label + ".null", new IrNull(result.type(), span)));
        }
        for (var constant : parameter.constants()) {
            String selected = label + ".token." + constant.token();
            cases.add(new IrSwitchCase(new IrConstant(IrType.I32, constant.token(), span), selected, span));
            blocks.add(new IrBasicBlock(selected, List.of(), new IrInvokeTerminator(
                    new IrEnsureTypeInitializedInstruction(constant.field().ownerClass(), span), selected + ".load", failure, span), span));
            var value = new IrValueReference(next++, constant.field().type(), span);
            List<IrInstruction> load = new ArrayList<>();
            load.add(new IrStaticFieldLoadInstruction(value, constant.field(), span));
            if (!value.type().equals(result.type())) {
                var exact = new IrValueReference(next++, result.type(), span);
                load.add(new IrReferenceConversionInstruction(exact, value, span));
                value = exact;
            }
            blocks.add(new IrBasicBlock(selected + ".load", load, new IrJump(label + ".join", span), span));
            incoming.add(new IrPhiIncoming(selected + ".load", value));
        }
        if (incoming.isEmpty()) throw new IllegalArgumentException("enum receiver has no realizable constant");
        blocks.add(new IrBasicBlock(label, List.of(), new IrSwitchTerminator(token, cases, invalid, span), span));
        blocks.add(new IrBasicBlock(label + ".join", List.of(new IrPhiInstruction(result, incoming, span)),
                new IrJump(continuation, span), span));
        return next;
    }
}
