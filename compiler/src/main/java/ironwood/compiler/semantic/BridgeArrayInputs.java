// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/** Array conversion permission composed with P0 borrowing and reclamation proofs. */
public final class BridgeArrayInputs {
    private BridgeArrayInputs() {}

    public record Contract(BridgeCallableId callable, Set<Integer> inputs, Set<BridgeCallableId> closure) {
        public Contract {
            inputs = Set.copyOf(inputs);
            closure = Set.copyOf(closure);
        }
    }

    public static boolean primitiveArray(IrType type) {
        return type.isArray() && type.elementType().isPrimitive();
    }

    public static BridgeProof<Contract> readOnly(CompilationArtifact artifact, BridgeCallableId callable) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()
                || !artifact.bridgeConstructionFacts().orElseThrow().matches(artifact.program().orElseThrow())) {
            return BridgeProof.unknown("array inputs require matching final semantic facts");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var roots = BridgeRootSet.resolve(program, java.util.List.of(callable));
        if (!roots.resolved()) return BridgeProof.unknown("array inputs require an exact resolved root");
        if (callable.kind() != IrCallableKind.METHOD || callable.result().isReference()) {
            return BridgeProof.rejected("read-only array inputs require a method without reference results");
        }
        Set<Integer> inputs = new LinkedHashSet<>();
        for (int index = 0; index < callable.parameters().size(); index++) {
            var type = callable.parameters().get(index);
            if (!type.isArray()) continue;
            if (!primitiveArray(type)) return BridgeProof.rejected("only one-dimensional primitive array inputs are supported");
            if (!facts.borrowsInput(callable, index)) {
                return BridgeProof.rejected("array input may escape, be returned or invalidated: parameter " + index);
            }
            var lifetime = BridgeNonReclamationAnalyzer.analyze(program, roots, type, facts);
            if (lifetime.status() != BridgeProof.Status.PROVED) return failed(lifetime.status(), lifetime.reason());
            inputs.add(index);
        }
        if (inputs.isEmpty()) return BridgeProof.rejected("array proof requires an array input");
        var retention = BridgeRetentionAnalyzer.analyze(program, roots, facts).get(callable);
        if (retention.status() != BridgeProof.Status.PROVED) return failed(retention.status(), retention.reason());
        for (var slot : retention.contract().orElseThrow().slots()) {
            if (slot.valueInputs().stream().anyMatch(inputs::contains)) {
                return BridgeProof.rejected("array input enters retaining storage");
            }
        }
        var targets = new BridgeCallTargets(program);
        var initialization = targets.initializers(callable.owner());
        if (!initialization.complete()) return BridgeProof.unknown("array entry initialization is unresolved");
        var pending = new ArrayDeque<IrFunction>(initialization.targets());
        pending.add(targets.function(callable.linkage()));
        Set<BridgeCallableId> closure = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            var function = pending.removeFirst();
            if (!closure.add(BridgeCallableId.of(function))) continue;
            for (var block : function.blocks()) {
                var instructions = new java.util.ArrayList<>(block.instructions());
                if (block.terminator() instanceof IrInvokeTerminator invoke) instructions.add(invoke.call());
                for (var instruction : instructions) {
                    var call = targets.resolve(instruction);
                    if (call != null) {
                        if (!call.complete()) return BridgeProof.unknown("unresolved array-call effect: " + function.linkageName());
                        pending.addAll(call.targets());
                    } else if (instruction instanceof IrArrayStoreInstruction || instruction instanceof IrSystemArrayCopyInstruction) {
                        return BridgeProof.rejected("read-only array closure contains a write: " + function.linkageName());
                    } else if (!localEffect(instruction)) {
                        return BridgeProof.unknown("unclassified array-call effect " + instruction.getClass().getSimpleName()
                                + " at " + function.linkageName());
                    }
                }
            }
        }
        return BridgeProof.proved(new Contract(callable, inputs, closure),
                "P0 borrowing, retention and non-reclamation plus closed read-only effects");
    }

    private static boolean localEffect(IrInstruction instruction) {
        return switch (instruction) {
            case IrArrayLoadInstruction ignored -> true;
            case IrArrayLengthInstruction ignored -> true;
            case IrArrayBoundsCheckInstruction ignored -> true;
            case IrArrayLengthCheckInstruction ignored -> true;
            case IrArrayTypeTestInstruction ignored -> true;
            case IrNullCheckInstruction ignored -> true;
            case IrFieldLoadInstruction ignored -> true;
            case IrFieldStoreInstruction ignored -> true;
            case IrStaticFieldLoadInstruction ignored -> true;
            case IrStaticFieldStoreInstruction ignored -> true;
            case IrAllocateInstruction ignored -> true;
            case IrArrayAllocateInstruction ignored -> true;
            case IrReferenceConversionInstruction ignored -> true;
            case IrPhiInstruction ignored -> true;
            case IrBinaryInstruction ignored -> true;
            case IrUnaryInstruction ignored -> true;
            case IrNumericConversionInstruction ignored -> true;
            case IrFloatingBitsInstruction ignored -> true;
            case IrMathUnaryInstruction ignored -> true;
            case IrMathBinaryInstruction ignored -> true;
            case IrInstanceOfInstruction ignored -> true;
            case IrTypeInitializedInstruction ignored -> true;
            case IrExceptionCaughtInstruction ignored -> true;
            case IrExceptionLandingPadInstruction ignored -> true;
            case IrAllocationCountInstruction ignored -> true;
            case IrLiveAllocationCountInstruction ignored -> true;
            case IrThrowableTraceInstruction trace -> trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.RELEASE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.COMMON;
            default -> false;
        };
    }

    private static <T> BridgeProof<T> failed(BridgeProof.Status status, String reason) {
        return status == BridgeProof.Status.REJECTED ? BridgeProof.rejected(reason) : BridgeProof.unknown(reason);
    }
}
