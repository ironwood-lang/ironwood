// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.semantic;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Complete borrowing authority for bounded foreign byte storage, never a native object. */
public final class BridgeByteViews {
    private BridgeByteViews() {}
    public static boolean view(IrType type) { return type.equals(IrByteViewInstruction.TYPE); }
    public record Contract(BridgeCallableId callable, Set<Integer> inputs,
                           Set<BridgeCallableId> closure, boolean observesIdentity) {
        public Contract { inputs = Set.copyOf(inputs); closure = Set.copyOf(closure); }
    }

    public static BridgeProof<Contract> analyze(CompilationArtifact artifact, BridgeCallableId callable) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty() || artifact.bridgeApiFacts().isEmpty()
                || !artifact.bridgeConstructionFacts().orElseThrow().matches(artifact.program().orElseThrow())
                || !artifact.bridgeApiFacts().orElseThrow().matches(artifact.program().orElseThrow())) {
            return BridgeProof.unknown("byte views require matching final semantic facts");
        }
        var declaration = artifact.bridgeApiFacts().orElseThrow().types().get(IrByteViewInstruction.TYPE.referenceName());
        if (declaration == null || !ByteViewIntrinsic.trusted(declaration.source())) {
            return BridgeProof.rejected("byte views require the exact bundled library declaration");
        }
        if (callable.kind() != IrCallableKind.METHOD || view(callable.result())
                || callable.owner().equals(IrByteViewInstruction.TYPE.referenceName())) {
            return BridgeProof.rejected("byte views are call-scoped method inputs only");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var roots = BridgeRootSet.resolve(program, List.of(callable));
        if (!roots.resolved()) return BridgeProof.unknown("byte view requires exact resolved entry");
        Set<Integer> inputs = new LinkedHashSet<>();
        for (int i = 0; i < callable.parameters().size(); i++) {
            if (!view(callable.parameters().get(i))) continue;
            if (!facts.borrowsInput(callable, i)) return BridgeProof.rejected("byte view may escape or be invalidated: parameter " + i);
            inputs.add(i);
        }
        if (inputs.isEmpty()) return BridgeProof.rejected("byte view proof requires a view input");
        var lifetime = BridgeNonReclamationAnalyzer.analyze(program, roots, IrByteViewInstruction.TYPE, facts);
        if (lifetime.status() != BridgeProof.Status.PROVED) return failed(lifetime.status(), lifetime.reason());
        var retention = BridgeRetentionAnalyzer.analyze(program, roots, facts).get(callable);
        if (retention.status() != BridgeProof.Status.PROVED) return failed(retention.status(), retention.reason());
        if (retention.contract().orElseThrow().slots().stream().anyMatch(slot -> slot.valueInputs().stream().anyMatch(inputs::contains))) {
            return BridgeProof.rejected("byte view enters retaining storage");
        }
        var targets = new BridgeCallTargets(program);
        var initializers = targets.initializers(callable.owner());
        if (!initializers.complete()) return BridgeProof.unknown("unresolved byte-view initializer");
        var pending = new ArrayDeque<IrFunction>(initializers.targets());
        pending.add(targets.function(callable.linkage()));
        Set<BridgeCallableId> closure = new LinkedHashSet<>();
        boolean identity = false;
        while (!pending.isEmpty()) {
            var function = pending.removeFirst();
            if (!closure.add(BridgeCallableId.of(function))) continue;
            for (var block : function.blocks()) {
                var instructions = new ArrayList<>(block.instructions());
                if (block.terminator() instanceof IrInvokeTerminator invoke) instructions.add(invoke.call());
                for (var instruction : instructions) {
                    if (instruction instanceof IrReferenceConversionInstruction conversion
                            && (view(conversion.value().type()) || view(conversion.result().type()))
                            && !conversion.value().type().equals(conversion.result().type())
                            && !(conversion.value() instanceof IrNull)) {
                        return BridgeProof.rejected("byte view cannot be converted to or from an object representation");
                    }
                    if (instruction instanceof IrInstanceOfInstruction test && view(test.value().type())) {
                        return BridgeProof.rejected("byte view runtime type operations are unsupported");
                    }
                    var call = targets.resolve(instruction);
                    if (instruction instanceof IrRollbackInstruction rollback) call = targets.cleanup(rollback.allocation(), true);
                    if (instruction instanceof IrFreeInstruction free) call = targets.cleanup(free.allocation(), false);
                    if (call != null) {
                        if (!call.complete()) return BridgeProof.unknown("unresolved byte-view call effects: " + function.linkageName());
                        pending.addAll(call.targets());
                    } else if (instruction instanceof IrByteViewInstruction) {
                        if (!function.ownerClass().equals(IrByteViewInstruction.TYPE.referenceName())) {
                            return BridgeProof.rejected("byte-view intrinsic outside trusted library body");
                        }
                    } else if (instruction instanceof IrArrayStoreInstruction store) {
                        if (!store.array().type().elementType().isPrimitive()) return BridgeProof.unknown("reference-array write in byte-view closure");
                    } else if (!BridgeArrayInputs.localEffect(instruction)) {
                        return BridgeProof.unknown("unclassified byte-view effect " + instruction.getClass().getSimpleName());
                    }
                    if (instruction instanceof IrBinaryInstruction binary
                            && (view(binary.left().type()) || view(binary.right().type()))
                            && !(binary.left() instanceof IrNull) && !(binary.right() instanceof IrNull)) identity = true;
                }
            }
        }
        return BridgeProof.proved(new Contract(callable, inputs, closure, identity),
                "P0 borrowing/retention/non-reclamation, exact library identity and complete bounded-view effects");
    }

    private static <T> BridgeProof<T> failed(BridgeProof.Status status, String reason) {
        return status == BridgeProof.Status.REJECTED ? BridgeProof.rejected(reason) : BridgeProof.unknown(reason);
    }
}
