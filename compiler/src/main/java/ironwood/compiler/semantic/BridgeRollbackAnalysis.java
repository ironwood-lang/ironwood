// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeConstructionContract;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Attributes typed failure edges to fresh, unpublished construction receivers. */
final class BridgeRollbackAnalysis {
    record Cleanup(BridgeConstructionContract construction, IrFunction function,
                   List<IrOperand> destructorObjects, List<IrOperand> elementArrays) {
        Cleanup {
            destructorObjects = List.copyOf(destructorObjects);
            elementArrays = List.copyOf(elementArrays);
        }
    }

    private final IrProgram program;
    private final BridgeConstructionFacts facts;
    private final BridgeCallTargets targets;

    BridgeRollbackAnalysis(IrProgram program, BridgeConstructionFacts facts) {
        this.program = program;
        this.facts = facts != null && facts.matches(program) ? facts : null;
        this.targets = new BridgeCallTargets(program);
    }

    Optional<Cleanup> entry(IrFunction constructor) {
        if (facts == null) return Optional.empty();
        var proof = facts.constructors().get(BridgeCallableId.of(constructor));
        if (proof == null || proof.status() != BridgeProof.Status.PROVED) return Optional.empty();
        var contract = proof.contract().orElseThrow();
        var owner = program.classes().stream().filter(type -> type.name().equals(constructor.ownerClass()))
                .findFirst();
        if (owner.isEmpty() || owner.orElseThrow().constructorRollback().isEmpty()) return Optional.empty();
        IrFunction cleanup = targets.function(owner.orElseThrow().constructorRollback().orElseThrow());
        if (cleanup == null || cleanup.kind() != IrCallableKind.CONSTRUCTOR_ROLLBACK
                || !cleanup.ownerClass().equals(constructor.ownerClass()) || cleanup.parameters().size() != 1
                || cleanup.blocks().size() != 1) return Optional.empty();
        var receiver = cleanup.parameters().getFirst().value();
        var block = cleanup.blocks().getFirst();
        if (!(block.terminator() instanceof IrReturnTerminator returned) || returned.value().isPresent()) {
            return Optional.empty();
        }
        var instructions = block.instructions();
        int index = 0;
        List<IrOperand> objects = new ArrayList<>();
        List<IrOperand> elements = new ArrayList<>();
        // Match the actual generated wrapper, not merely its callable kind.
        for (var field : contract.ownedStorageFields().reversed()) {
            if (index >= instructions.size() || !(instructions.get(index++) instanceof IrFieldLoadInstruction load)
                    || !load.receiver().equals(receiver) || !load.field().equals(field)) return Optional.empty();
            if (contract.ownedElementFields().contains(field)) {
                if (index >= instructions.size()
                        || !(instructions.get(index++) instanceof IrDestroyArrayElementsInstruction destroy)
                        || !destroy.array().equals(load.result())) return Optional.empty();
                elements.add(load.result());
            }
            if (index >= instructions.size() || !(instructions.get(index++) instanceof IrFreeInstruction free)
                    || !free.allocation().equals(load.result())) return Optional.empty();
            objects.add(load.result());
        }
        if (index < instructions.size() && instructions.get(index) instanceof IrThrowableTraceInstruction trace) {
            if (trace.operation() != IrThrowableTraceInstruction.Operation.RELEASE
                    || !trace.arguments().equals(List.of(receiver))) return Optional.empty();
            index++;
        }
        if (index != instructions.size() - 1
                || !(instructions.get(index) instanceof IrRawDeallocateInstruction raw)
                || !raw.allocation().equals(receiver)) return Optional.empty();
        return Optional.of(new Cleanup(contract, cleanup, objects, elements));
    }

    Optional<Cleanup> unwind(IrFunction caller, IrBasicBlock rollbackBlock, IrRollbackInstruction rollback) {
        if (facts != null && facts.generatedConstructor(BridgeCallableId.of(caller)).isPresent()) {
            return generatedUnwind(caller, rollbackBlock, rollback);
        }
        if (facts == null || rollbackBlock.instructions().size() != 2
                || !(rollbackBlock.instructions().getFirst() instanceof IrExceptionLandingPadInstruction landing)
                || !rollbackBlock.instructions().getLast().equals(rollback)
                || !(rollbackBlock.terminator() instanceof IrThrowTerminator rethrow)
                || !rethrow.exception().equals(landing.exceptionObject())) return Optional.empty();
        List<IrBasicBlock> predecessors = caller.blocks().stream()
                .filter(block -> successors(block.terminator()).contains(rollbackBlock.label())).toList();
        if (predecessors.size() != 1) return Optional.empty();
        var predecessor = predecessors.getFirst();
        if (!(predecessor.terminator() instanceof IrInvokeTerminator invoke)
                || !invoke.unwindTarget().equals(rollbackBlock.label())
                || invoke.normalTarget().equals(rollbackBlock.label())
                || !(invoke.call() instanceof IrCallInstruction call) || call.arguments().isEmpty()
                || predecessor.instructions().isEmpty()
                || !(predecessor.instructions().getLast() instanceof IrAllocateInstruction allocation)
                || !call.arguments().getFirst().equals(allocation.result())
                || !rollback.allocation().equals(allocation.result())) return Optional.empty();
        // The immediately preceding allocation cannot have been published before
        // the constructor; all further non-publication comes from semantic facts.
        IrFunction constructor = targets.function(call.targetLinkageName());
        if (constructor == null || !constructor.constructor()
                || !constructor.ownerClass().equals(allocation.className())) return Optional.empty();
        return entry(constructor);
    }

    private Optional<Cleanup> generatedUnwind(IrFunction caller, IrBasicBlock rollbackBlock, IrRollbackInstruction rollback) {
        var source = facts.generatedConstructor(BridgeCallableId.of(caller)).orElseThrow();
        var constructor = targets.function(source.linkage());
        if (constructor == null || !BridgeCallableId.of(constructor).equals(source)) return Optional.empty();
        var instructions = rollbackBlock.instructions();
        // No publication of the fresh receiver occurs before rollback. The fixed
        // adapter stores the caught throwable and clears its result slot first.
        if (instructions.size() < 5 || !instructions.get(4).equals(rollback)
                || !(instructions.get(0) instanceof IrExceptionLandingPadInstruction landing)
                || !(instructions.get(1) instanceof IrExceptionCaughtInstruction caught)
                || !caught.exception().equals(landing.exceptionObject())
                || !(instructions.get(2) instanceof IrBridgeResultStoreInstruction exception)
                || exception.slot() != IrBridgeResultStoreInstruction.Slot.EXCEPTION
                || !exception.value().equals(landing.exceptionObject())
                || !(instructions.get(3) instanceof IrBridgeResultStoreInstruction result)
                || result.slot() != IrBridgeResultStoreInstruction.Slot.VALUE || !(result.value() instanceof IrNull)
                || !result.value().type().equals(rollback.allocation().type())
                || !result.frameAddress().equals(exception.frameAddress())) return Optional.empty();
        var predecessors = predecessors(caller, rollbackBlock);
        if (predecessors.size() != 1) return Optional.empty();
        var target = predecessors.getFirst();
        if (!target.instructions().isEmpty() || !(target.terminator() instanceof IrInvokeTerminator invoke)
                || !invoke.unwindTarget().equals(rollbackBlock.label()) || invoke.normalTarget().equals(rollbackBlock.label())
                || !(invoke.call() instanceof IrCallInstruction call) || !call.targetLinkageName().equals(source.linkage())
                || call.arguments().isEmpty() || !call.arguments().getFirst().equals(rollback.allocation())) return Optional.empty();
        var allocations = predecessors(caller, target);
        if (allocations.size() != 1) return Optional.empty();
        var allocationBlock = allocations.getFirst();
        if (!(allocationBlock.terminator() instanceof IrInvokeTerminator allocated)
                || !allocated.normalTarget().equals(target.label()) || allocated.unwindTarget().equals(target.label())
                || !(allocated.call() instanceof IrAllocateInstruction allocation)
                || !allocation.result().equals(rollback.allocation()) || !allocation.className().equals(source.owner())) {
            return Optional.empty();
        }
        return entry(constructor);
    }

    private static List<IrBasicBlock> predecessors(IrFunction caller, IrBasicBlock target) {
        return caller.blocks().stream().filter(block -> successors(block.terminator()).contains(target.label())).toList();
    }

    private static List<String> successors(IrTerminator terminator) {
        return switch (terminator) {
            case IrJump jump -> List.of(jump.target());
            case IrBranch branch -> List.of(branch.trueTarget(), branch.falseTarget());
            case IrInvokeTerminator invoke -> List.of(invoke.normalTarget(), invoke.unwindTarget());
            case IrSwitchTerminator selection -> java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(selection.defaultTarget()),
                    selection.cases().stream().map(IrSwitchCase::target)).toList();
            case IrThrowTerminator thrown -> java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(thrown.normalTarget()), thrown.unwindTarget().stream()).toList();
            case IrReturnTerminator ignored -> List.of();
            case IrUnreachable ignored -> List.of();
        };
    }
}
