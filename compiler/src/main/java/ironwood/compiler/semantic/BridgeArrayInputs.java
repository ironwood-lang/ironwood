// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Optional;

/** Array conversion permission composed with P0 borrowing and reclamation proofs. */
public final class BridgeArrayInputs {
    private BridgeArrayInputs() {}

    public record Contract(BridgeCallableId callable, Set<Integer> inputs, Set<BridgeCallableId> closure,
                           boolean mutable, Optional<BridgeResultOriginContract> result) {
        public Contract {
            inputs = Set.copyOf(inputs);
            closure = Set.copyOf(closure);
        }
    }

    public static boolean primitiveArray(IrType type) {
        return type.isArray() && type.elementType().isPrimitive();
    }

    public static BridgeProof<Contract> readOnly(CompilationArtifact artifact, BridgeCallableId callable) {
        return analyze(artifact, callable, false);
    }

    /** Analysis only until the caller also supplies copy-back and result transport. */
    public static BridgeProof<Contract> values(CompilationArtifact artifact, BridgeCallableId callable) {
        return analyze(artifact, callable, true);
    }

    private static BridgeProof<Contract> analyze(CompilationArtifact artifact, BridgeCallableId callable, boolean mutableValues) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()
                || !artifact.bridgeConstructionFacts().orElseThrow().matches(artifact.program().orElseThrow())) {
            return BridgeProof.unknown("array inputs require matching final semantic facts");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var roots = BridgeRootSet.resolve(program, java.util.List.of(callable));
        if (!roots.resolved()) return BridgeProof.unknown("array inputs require an exact resolved root");
        boolean arrayResult = mutableValues && primitiveArray(callable.result());
        if (callable.kind() != IrCallableKind.METHOD || callable.result().isArray() && !arrayResult) {
            return BridgeProof.rejected("array values require methods with separately proved result transport");
        }
        Set<Integer> inputs = new LinkedHashSet<>();
        for (int index = 0; index < callable.parameters().size(); index++) {
            var type = callable.parameters().get(index);
            if (!type.isArray()) continue;
            if (!primitiveArray(type)) return BridgeProof.rejected("only one-dimensional primitive array inputs are supported");
            if (!(arrayResult ? facts.borrowsThroughResult(callable, index) : facts.borrowsInput(callable, index))) {
                return BridgeProof.rejected("array input may escape, be returned or invalidated: parameter " + index);
            }
            var lifetime = BridgeNonReclamationAnalyzer.analyze(program, roots, type, facts);
            if (lifetime.status() != BridgeProof.Status.PROVED) return failed(lifetime.status(), lifetime.reason());
            inputs.add(index);
        }
        if (inputs.isEmpty() && !arrayResult) return BridgeProof.rejected("array proof requires an array input or result");
        Optional<BridgeResultOriginContract> result = Optional.empty();
        if (arrayResult) {
            var origin = facts.resultOrigins().get(callable);
            if (origin == null) return BridgeProof.unknown("missing array result origins");
            if (origin.status() != BridgeProof.Status.PROVED) return failed(origin.status(), origin.reason());
            var contract = origin.contract().orElseThrow();
            if (contract.kind() == BridgeResultOriginContract.Kind.DEPENDENT_VIEW
                    || contract.inputs().stream().anyMatch(index -> !inputs.contains(index)
                    || !callable.parameters().get(index).equals(callable.result()))) {
                return BridgeProof.rejected("array result requires an exact input alias or invocation-owned allocation");
            }
            var lifetime = BridgeNonReclamationAnalyzer.analyze(program, roots, callable.result(), facts);
            if (lifetime.status() != BridgeProof.Status.PROVED) return failed(lifetime.status(), lifetime.reason());
            result = Optional.of(contract);
        }
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
        boolean mutable = false;
        while (!pending.isEmpty()) {
            var function = pending.removeFirst();
            if (!closure.add(BridgeCallableId.of(function))) continue;
            for (var block : function.blocks()) {
                var instructions = new java.util.ArrayList<>(block.instructions());
                if (block.terminator() instanceof IrInvokeTerminator invoke) instructions.add(invoke.call());
                for (var instruction : instructions) {
                    var call = targets.resolve(instruction);
                    // Compiler rollback may itself call cleanup code. P0 proves
                    // reclamation; its complete effects still belong to this closure.
                    if (instruction instanceof IrRollbackInstruction rollback) call = targets.cleanup(rollback.allocation(), true);
                    if (instruction instanceof IrFreeInstruction free) call = targets.cleanup(free.allocation(), false);
                    if (call != null) {
                        if (!call.complete()) return BridgeProof.unknown("unresolved array-call effect: " + function.linkageName());
                        pending.addAll(call.targets());
                    } else if (instruction instanceof IrArrayStoreInstruction store) {
                        if (!mutableValues) return BridgeProof.rejected("read-only array closure contains a write: " + function.linkageName());
                        if (!primitiveArray(store.array().type())) return BridgeProof.unknown("nonprimitive array write in copied-value closure");
                        mutable = true;
                    } else if (instruction instanceof IrSystemArrayCopyInstruction) {
                        return BridgeProof.unknown("arraycopy requires a separately proved checked runtime boundary");
                    } else if (!localEffect(instruction)) {
                        return BridgeProof.unknown("unclassified array-call effect " + instruction.getClass().getSimpleName()
                                + " at " + function.linkageName());
                    }
                }
            }
        }
        return BridgeProof.proved(new Contract(callable, inputs, closure, mutable, result),
                "P0 borrowing, retention, result origins and non-reclamation plus closed array effects");
    }

    private static boolean localEffect(IrInstruction instruction) {
        return switch (instruction) {
            // P0 already excludes reclamation of every copied input/result type.
            // These fixed releases have no remaining array-write/callback effect.
            case IrRawDeallocateInstruction ignored -> true;
            case IrReleaseOwnedThrowableMessageInstruction ignored -> true;
            case IrReleaseOwnedToStringResultInstruction ignored -> true;
            case IrStringCopyInstruction ignored -> true;
            case IrStringCharAtInstruction ignored -> true;
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
