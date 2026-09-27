// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Analysis-only reachable edges, using complete nonraising and allocation-free callee proofs. */
final class BridgeControlFlow {
    private final Map<String, List<IrBasicBlock>> views;

    BridgeControlFlow(IrProgram program) {
        var targets = new BridgeCallTargets(program);
        var effects = new ClosedWorldEffectAnalyzer(program.functions(), program.classes());
        effects.analyze();
        var nonraising = new LinkedHashSet<String>();
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (var function : program.functions()) {
            boolean complete = effects.nonThrowingAndAllocationFree(function.linkageName());
            var called = new LinkedHashSet<String>();
            for (var block : function.blocks()) {
                if (block.terminator() instanceof IrThrowTerminator thrown && thrown.unwindTarget().isEmpty()) complete = false;
                var instructions = new ArrayList<>(block.instructions());
                if (block.terminator() instanceof IrInvokeTerminator invoke) instructions.add(invoke.call());
                for (var instruction : instructions) {
                    var call = call(targets, instruction);
                    if (call != null) {
                        complete &= call.complete();
                        call.targets().forEach(target -> called.add(target.linkageName()));
                    } else complete &= nonraisingNative(instruction);
                }
            }
            if (complete) nonraising.add(function.linkageName());
            dependencies.put(function.linkageName(), Set.copyOf(called));
        }
        // A favorable summary cannot hide a missing target or unclassified
        // intrinsic anywhere in the closure, including a recursive component.
        boolean changed;
        do {
            changed = nonraising.removeIf(name -> !nonraising.containsAll(dependencies.get(name)));
        } while (changed);
        var resolved = new LinkedHashMap<String, List<IrBasicBlock>>();
        for (var function : program.functions()) resolved.put(function.linkageName(), view(function, targets, nonraising));
        views = Map.copyOf(resolved);
    }

    List<IrBasicBlock> blocks(IrFunction function) { return views.get(function.linkageName()); }

    private static List<IrBasicBlock> view(IrFunction function, BridgeCallTargets targets, Set<String> nonraising) {
        Map<String, IrBasicBlock> blocks = new LinkedHashMap<>();
        function.blocks().forEach(block -> blocks.put(block.label(), block));
        Map<String, Set<String>> incoming = new LinkedHashMap<>();
        var reachable = new LinkedHashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(function.blocks().getFirst().label());
        while (!pending.isEmpty()) {
            String label = pending.removeFirst();
            if (!reachable.add(label)) continue;
            var successors = new LinkedHashSet<String>();
            var terminator = blocks.get(label).terminator();
            if (terminator instanceof IrJump jump) successors.add(jump.target());
            else if (terminator instanceof IrBranch branch) {
                successors.add(branch.trueTarget());
                successors.add(branch.falseTarget());
            } else if (terminator instanceof IrSwitchTerminator switched) {
                successors.add(switched.defaultTarget());
                switched.cases().forEach(arm -> successors.add(arm.target()));
            } else if (terminator instanceof IrThrowTerminator thrown) thrown.unwindTarget().ifPresent(successors::add);
            else if (terminator instanceof IrInvokeTerminator invoke) {
                successors.add(invoke.normalTarget());
                var call = call(targets, invoke.call());
                boolean proved = call != null ? call.complete() && call.targets().stream()
                        .allMatch(target -> nonraising.contains(target.linkageName())) : nonraisingNative(invoke.call());
                if (!proved) successors.add(invoke.unwindTarget());
            }
            for (String target : successors) {
                incoming.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(label);
                pending.add(target);
            }
        }
        return function.blocks().stream().filter(block -> reachable.contains(block.label())).map(block -> {
            List<IrInstruction> instructions = block.instructions().stream().map(instruction -> {
                if (!(instruction instanceof IrPhiInstruction phi)) return instruction;
                return (IrInstruction) new IrPhiInstruction(phi.result(), phi.incoming().stream()
                        .filter(value -> incoming.getOrDefault(block.label(), Set.of()).contains(value.predecessor())).toList(),
                        phi.sourceSpan());
            }).toList();
            return new IrBasicBlock(block.label(), instructions, block.terminator(), block.sourceSpan());
        }).toList();
    }

    private static BridgeCallTargets.Call call(BridgeCallTargets targets, IrInstruction instruction) {
        if (instruction instanceof IrFreeInstruction free) return targets.cleanup(free.allocation(), false);
        if (instruction instanceof IrRollbackInstruction rollback) return targets.cleanup(rollback.allocation(), true);
        if (instruction instanceof IrDestroyArrayElementsInstruction destroy) {
            return targets.cleanup(new IrNull(destroy.array().type().elementType(), instruction.sourceSpan()), false);
        }
        return targets.resolve(instruction);
    }

    private static boolean nonraisingNative(IrInstruction instruction) {
        return switch (instruction) {
            case IrFieldLoadInstruction ignored -> true;
            case IrFieldStoreInstruction ignored -> true;
            case IrStaticFieldLoadInstruction ignored -> true;
            case IrStaticFieldStoreInstruction ignored -> true;
            case IrArrayLoadInstruction ignored -> true;
            case IrArrayStoreInstruction ignored -> true;
            case IrArrayLengthInstruction ignored -> true;
            case IrReferenceConversionInstruction ignored -> true;
            case IrPhiInstruction ignored -> true;
            case IrBinaryInstruction ignored -> true;
            case IrUnaryInstruction ignored -> true;
            case IrNumericConversionInstruction ignored -> true;
            case IrInstanceOfInstruction ignored -> true;
            case IrTypeInitializedInstruction ignored -> true;
            case IrIdentityHashCodeInstruction ignored -> true;
            case IrRawDeallocateInstruction ignored -> true;
            case IrReleaseOwnedToStringResultInstruction ignored -> true;
            case IrReleaseOwnedThrowableMessageInstruction ignored -> true;
            case IrExceptionLandingPadInstruction ignored -> true;
            case IrExceptionCaughtInstruction ignored -> true;
            case IrAllocationCountInstruction ignored -> true;
            case IrLiveAllocationCountInstruction ignored -> true;
            // Checks, allocation, native I/O and unclassified operations can
            // raise. Observing/non-retaining does not imply nonraising.
            default -> false;
        };
    }
}
