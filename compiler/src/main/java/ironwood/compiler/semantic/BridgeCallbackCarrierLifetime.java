// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * D227 lifetime of compiler-owned carriers created by one invocation. No source
 * reclamation permission is granted. Unknown uses retain the carrier and its
 * Java global reference for process lifetime, just like native thrown objects.
 */
public final class BridgeCallbackCarrierLifetime {
    private final IrProgram program;
    private final BridgeRootSet roots;
    private final Set<String> retentionReasons;

    private BridgeCallbackCarrierLifetime(IrProgram program, BridgeRootSet roots, Set<String> reasons) {
        this.program = program;
        this.roots = roots;
        retentionReasons = Set.copyOf(reasons);
    }

    public boolean matches(IrProgram candidate, BridgeRootSet requested) {
        return program.equals(candidate) && roots.equals(requested.revalidate(candidate));
    }

    /** Only after the protected entry ends all native aliases and unwind uses. */
    public boolean invocationOwned() { return retentionReasons.isEmpty(); }
    public Set<String> retentionReasons() { return retentionReasons; }

    public static BridgeCallbackCarrierLifetime analyze(IrProgram program, BridgeRootSet requested) {
        return analyze(program, requested, false);
    }

    public static BridgeCallbackCarrierLifetime analyzeManaged(ironwood.compiler.CompilationArtifact artifact,
            ironwood.compiler.bridge.BridgeListenerProxies proxies, BridgeRootSet requested) {
        var associations = BridgeRetentionAnalyzer.managedCarrierAssociations(artifact, proxies, requested);
        var result = analyze(artifact.program().orElseThrow(), requested, associations.status() == ironwood.compiler.bridge.BridgeProof.Status.PROVED);
        if (associations.status() == ironwood.compiler.bridge.BridgeProof.Status.PROVED) return result;
        var reasons = new LinkedHashSet<>(result.retentionReasons());
        reasons.add(associations.reason());
        return new BridgeCallbackCarrierLifetime(result.program, result.roots, reasons);
    }

    private static BridgeCallbackCarrierLifetime analyze(IrProgram program, BridgeRootSet requested, boolean containedAssociations) {
        var roots = requested.revalidate(program);
        if (!roots.resolved()) throw new IllegalArgumentException("carrier lifetime requires resolved native roots");
        var calls = new BridgeCallTargets(program);
        var reachability = BridgeCallbackReachability.analyze(program);
        var reasons = new LinkedHashSet<String>();
        var pending = new ArrayList<IrFunction>();
        for (var root : roots.roots()) {
            var function = calls.function(root.callable().linkage());
            pending.add(function);
            initialization(calls.initializers(root.callable().owner()), reachability, pending, reasons);

        }
        var visited = new LinkedHashSet<String>();
        for (int index = 0; index < pending.size(); index++) {
            var function = pending.get(index);
            if (!visited.add(function.linkageName())) continue;
            var effect = reachability.functions().get(BridgeCallableId.of(function));
            if (!effect.complete()) reasons.addAll(effect.unknown());
            inspectHandlerUses(function, reasons, containedAssociations);
            operations(function).forEach(operation -> {
                if (operation instanceof IrForeignCallInstruction) return;
                if (operation instanceof IrEnsureTypeInitializedInstruction ensure) {
                    initialization(calls.initializers(ensure.typeName()), reachability, pending, reasons);
                    return;
                }
                var edge = calls.resolve(operation);
                boolean cleanup = false;

                if (edge == null) return;
                if (!edge.complete()) reasons.add("unresolved carrier edge: " + function.linkageName());
                pending.addAll(edge.targets());
                if (cleanup && edge.targets().stream().anyMatch(target ->
                        reachability.functions().get(BridgeCallableId.of(target)).requiresContext())) {
                    reasons.add("carrier may survive callback-capable cleanup: " + function.linkageName());
                }
            });
        }
        return new BridgeCallbackCarrierLifetime(program, roots, reasons);
    }

    private static void initialization(BridgeCallTargets.Initializers initialization,
            BridgeCallbackReachability reachability, List<IrFunction> pending, Set<String> reasons) {
        if (!initialization.complete()) reasons.add("unresolved carrier initialization");
        pending.addAll(initialization.targets());
        if (initialization.targets().stream().anyMatch(target ->
                reachability.functions().get(BridgeCallableId.of(target)).requiresContext())) {
            // Failed native initialization caches its exception outside the
            // invocation even when its body contains no explicit field store.
            reasons.add("failed initialization can retain a callback carrier");
        }
    }

    private static void inspectHandlerUses(IrFunction function, Set<String> reasons, boolean containedAssociations) {
        var operations = operations(function).toList();
        var aliases = new LinkedHashSet<Integer>();
        for (var operation : operations) {
            if (operation instanceof IrExceptionLandingPadInstruction landing) aliases.add(landing.exceptionObject().id());
        }
        boolean changed;
        do {
            changed = false;
            for (var operation : operations) {
                if (operation instanceof IrReferenceConversionInstruction conversion && alias(conversion.value(), aliases)) {
                    changed |= aliases.add(conversion.result().id());
                } else if (operation instanceof IrNullCheckInstruction checked && alias(checked.receiver(), aliases)) {
                    changed |= aliases.add(checked.result().id());
                } else if (operation instanceof IrPhiInstruction phi
                        && phi.incoming().stream().anyMatch(incoming -> alias(incoming.value(), aliases))) {
                    changed |= aliases.add(phi.result().id());
                } else if (containedAssociations && operation instanceof IrAddSecondaryExceptionInstruction association
                        && alias(association.secondary(), aliases) && association.primary() instanceof IrValueReference reference) {
                    changed |= aliases.add(reference.id());
                }
            }
        } while (changed);
        if (aliases.isEmpty()) return;
        for (var operation : operations) {
            if (observation(operation) || containedAssociations && operation instanceof IrAddSecondaryExceptionInstruction) continue;
            boolean[] used = {false};
            new IrCfgRenamer(value -> {
                used[0] |= aliases.contains(value.id());
                return value;
            }, label -> label).instruction(operation);
            if (used[0]) reasons.add("carrier reference used by " + operation.getClass().getSimpleName()
                    + " in " + function.linkageName() + ":" + operation.sourceSpan().start().line());
        }
        for (var block : function.blocks()) {
            if (block.terminator() instanceof IrReturnTerminator returned
                    && returned.value().stream().anyMatch(value -> alias(value, aliases))) {
                reasons.add("carrier returned as an ordinary native value: " + function.linkageName());
            }
            // A rethrow is safe only as a transfer to another analyzed handler or
            // the protected outer entry. All reachable handlers are inspected;
            // the caller must wait for that entry before reclaiming any carrier.
        }
    }

    private static boolean observation(IrInstruction operation) {
        return operation instanceof IrExceptionLandingPadInstruction
                || operation instanceof IrExceptionCaughtInstruction
                || operation instanceof IrInstanceOfInstruction
                || operation instanceof IrReferenceConversionInstruction
                || operation instanceof IrNullCheckInstruction
                || operation instanceof IrPhiInstruction
                || operation instanceof IrBinaryInstruction binary
                && (binary.operator() == IrBinaryOperator.EQUAL || binary.operator() == IrBinaryOperator.NOT_EQUAL);
    }

    private static boolean alias(IrOperand value, Set<Integer> aliases) {
        return value instanceof IrValueReference reference && aliases.contains(reference.id());
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(block -> Stream.concat(block.instructions().stream(),
                block.terminator() instanceof IrInvokeTerminator invoke ? Stream.of(invoke.call()) : Stream.empty()));
    }
}
