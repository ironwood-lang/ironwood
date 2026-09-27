// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;

/** Shared D194 active-use blocks; the caller supplies proved exact token alternatives. */
final class BridgeEnumConversion {
    private BridgeEnumConversion() {}

    static int append(List<IrBasicBlock> blocks, BridgeEnumConversions.Parameter parameter,
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

    /** Convert a nullable native result by the paired public fields, never storage addresses or ordinals. */
    static int appendResult(List<IrBasicBlock> blocks, BridgeEnumConversions.Result mapping,
            IrValueReference nativeResult, IrValueReference token, int next, String label, String continuation,
            String failure, String invalid, SourceSpan span) {
        var present = new IrValueReference(next++, IrType.I1, span);
        blocks.add(new IrBasicBlock(label, List.of(new IrNullCheckInstruction(present, nativeResult, span)),
                new IrBranch(present, mapping.constants().isEmpty() ? invalid : label + ".initialize", label + ".null", span), span));
        blocks.add(new IrBasicBlock(label + ".null", List.of(), new IrJump(label + ".join", span), span));
        List<IrPhiIncoming> incoming = new ArrayList<>();
        incoming.add(new IrPhiIncoming(label + ".null", new IrConstant(IrType.I32, -1, span)));
        if (!mapping.constants().isEmpty()) {
            blocks.add(new IrBasicBlock(label + ".initialize", List.of(), new IrInvokeTerminator(
                    new IrEnsureTypeInitializedInstruction(mapping.declaredType().referenceName(), span),
                    label + ".match.0", failure, span), span));
        }
        for (int index = 0; index < mapping.constants().size(); index++) {
            var constant = mapping.constants().get(index);
            var reference = new IrValueReference(next++, mapping.declaredType(), span);
            var equal = new IrValueReference(next++, IrType.I1, span);
            String selected = label + ".token." + constant.token();
            blocks.add(new IrBasicBlock(label + ".match." + index, List.of(
                    new IrStaticFieldLoadInstruction(reference, constant.field(), span),
                    new IrBinaryInstruction(equal, IrBinaryOperator.EQUAL, nativeResult, reference, span)),
                    new IrBranch(equal, selected, index + 1 == mapping.constants().size()
                            ? invalid : label + ".match." + (index + 1), span), span));
            blocks.add(new IrBasicBlock(selected, List.of(), new IrJump(label + ".join", span), span));
            incoming.add(new IrPhiIncoming(selected, new IrConstant(IrType.I32, constant.token(), span)));
        }
        blocks.add(new IrBasicBlock(label + ".join", List.of(new IrPhiInstruction(token, incoming, span)),
                new IrJump(continuation, span), span));
        return next;
    }
}
