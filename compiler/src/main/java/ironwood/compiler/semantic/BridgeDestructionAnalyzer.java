// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Proves the destruction operation separately from ordinary bridge invocation roots. */
public final class BridgeDestructionAnalyzer {
    private BridgeDestructionAnalyzer() {}

    public static BridgeProof<BridgeDestructionContract> analyze(
            CompilationArtifact artifact, BridgeRootSet roots, IrType type) {
        var ownership = BridgeRootRetentionAnalyzer.analyze(artifact, roots);
        if (ownership.status() != BridgeProof.Status.PROVED) return failure(ownership.status(), ownership.reason());
        var contract = ownership.contract().orElseThrow();
        if (!contract.constructedRootTypes().contains(type)) return BridgeProof.rejected("type is not an admitted constructed root");
        var program = artifact.program().orElseThrow();
        var targets = new BridgeCallTargets(program);
        var span = roots.roots().getFirst().span();
        var cleanup = targets.cleanup(new IrNull(type, span), false);
        if (!cleanup.complete()) return BridgeProof.unknown("incomplete root destruction descriptor");
        var pending = new ArrayDeque<>(cleanup.targets());
        var visited = new LinkedHashSet<IrFunction>();
        var effects = new ClosedWorldEffectAnalyzer(program.functions(), program.classes());
        effects.analyze();
        while (!pending.isEmpty()) {
            var function = pending.removeFirst();
            if (!visited.add(function)) continue;
            if (!effects.nonThrowingAndAllocationFree(function.linkageName())) {
                return BridgeProof.rejected("destruction may throw or allocate: " + function.linkageName());
            }
            List<IrInstruction> instructions = new ArrayList<>();
            for (var block : function.blocks()) {
                instructions.addAll(block.instructions());
                if (block.terminator() instanceof IrInvokeTerminator invoke) instructions.add(invoke.call());
            }
            for (var instruction : instructions) {
                var call = targets.resolve(instruction);
                if (instruction instanceof IrFreeInstruction free) call = targets.cleanup(free.allocation(), false);
                if (instruction instanceof IrDestroyArrayElementsInstruction destroy) {
                    call = targets.cleanup(new IrNull(destroy.array().type().elementType(), instruction.sourceSpan()), false);
                }
                if (call != null) {
                    if (!call.complete()) return BridgeProof.unknown("unresolved destruction dependency: " + function.linkageName());
                    pending.addAll(call.targets());
                } else if (!nonRaising(instruction)) {
                    return BridgeProof.unknown("unclassified destruction operation " + instruction.getClass().getSimpleName()
                            + " at " + function.sourceFileName() + ":" + instruction.sourceSpan().start().line());
                }
            }
        }
        if (!cleanup.targets().isEmpty()) {
            var cleanupRoots = BridgeRootSet.resolve(program, cleanup.targets().stream().map(BridgeCallableId::of).toList());
            var retention = BridgeRetentionAnalyzer.analyze(program, cleanupRoots, artifact.bridgeConstructionFacts().orElseThrow());
            for (var proof : retention.values()) {
                if (proof.status() != BridgeProof.Status.PROVED) return failure(proof.status(), proof.reason());
                if (proof.contract().orElseThrow().slots().stream().anyMatch(slot -> slot.holderInput() != 0
                        || !slot.valueInputs().isEmpty() || !slot.field().ownerClass().equals(type.referenceName()))) {
                    return BridgeProof.rejected("destruction can introduce dependencies or mutate another root");
                }
            }
        }
        return BridgeProof.proved(new BridgeDestructionContract(contract, type, visited.stream().map(BridgeCallableId::of)
                .sorted(Comparator.comparing(BridgeCallableId::linkage)).toList()),
                "complete descriptor cleanup is nonthrowing, allocation-free and has no callback or new retention effects");
    }

    private static boolean nonRaising(IrInstruction instruction) {
        return switch (instruction) {
            case IrFieldLoadInstruction ignored -> true;
            case IrFieldStoreInstruction ignored -> true;
            case IrStaticFieldLoadInstruction ignored -> true;
            case IrStaticFieldStoreInstruction ignored -> true;
            case IrArrayLoadInstruction ignored -> true;
            case IrArrayStoreInstruction ignored -> true;
            case IrReferenceConversionInstruction ignored -> true;
            case IrPhiInstruction ignored -> true;
            case IrBinaryInstruction ignored -> true;
            case IrUnaryInstruction ignored -> true;
            case IrNumericConversionInstruction ignored -> true;
            case IrNullCheckInstruction ignored -> true;
            case IrArrayBoundsCheckInstruction ignored -> true;
            case IrArrayLengthCheckInstruction ignored -> true;
            case IrArrayLengthInstruction ignored -> true;
            case IrTypeInitializedInstruction ignored -> true;
            case IrExceptionLandingPadInstruction ignored -> true;
            case IrExceptionCaughtInstruction ignored -> true;
            case IrRawDeallocateInstruction ignored -> true;
            default -> false;
        };
    }

    private static BridgeProof<BridgeDestructionContract> failure(BridgeProof.Status status, String reason) {
        return status == BridgeProof.Status.REJECTED ? BridgeProof.rejected(reason) : BridgeProof.unknown(reason);
    }
}
