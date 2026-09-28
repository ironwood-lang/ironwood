// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeCallbackCarrierLifetime;
import ironwood.compiler.semantic.BridgeCleanupAnalyzer;

import java.util.List;
import java.util.Optional;

/** Invocation-bound destruction of newly created carriers, never arbitrary native exceptions. */
public final class BridgeCallbackCarrierCleanup {
    private final IrProgram program;
    private final BridgeRootSet roots;
    private final IrFunction destruction;

    private BridgeCallbackCarrierCleanup(IrProgram program, BridgeRootSet roots, IrFunction destruction) {
        this.program = program;
        this.roots = roots;
        this.destruction = destruction;
    }

    public IrFunction destruction() { return destruction; }
    public boolean matches(CompilationArtifact artifact, BridgeRootSet requested) {
        return artifact.valid() && artifact.program().filter(program::equals).isPresent()
                && roots.equals(requested.revalidate(program));
    }

    public static BridgeCallbackCarrierCleanup prove(CompilationArtifact artifact,
            BridgeCallbackCarrierSources source, BridgeRootSet requested) {
        var bound = source.bind(artifact);
        var program = artifact.program().orElseThrow();
        var roots = requested.revalidate(program);
        var lifetime = BridgeCallbackCarrierLifetime.analyze(program, roots);
        if (!lifetime.invocationOwned()) {
            throw new IllegalArgumentException("callback carrier may outlive invocation: " + lifetime.retentionReasons());
        }
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        if (!facts.matches(program)) throw new IllegalArgumentException("carrier cleanup requires matching construction facts");
        var origin = facts.resultOrigins().get(BridgeCallableId.of(bound.factory()));
        if (origin == null || origin.status() != BridgeProof.Status.PROVED
                || origin.contract().orElseThrow().kind() != BridgeResultOriginContract.Kind.FRESH_ROOT
                || origin.contract().orElseThrow().nullable()) {
            throw new IllegalArgumentException("carrier factory does not prove unique fresh storage");
        }
        var constructors = program.functions().stream().filter(function -> function.ownerClass().equals(source.type().referenceName())
                && function.constructor()).map(BridgeCallableId::of).toList();
        if (constructors.size() != 1 || !facts.constructors().containsKey(constructors.getFirst())
                || facts.constructors().get(constructors.getFirst()).status() != BridgeProof.Status.PROVED) {
            throw new IllegalArgumentException("carrier construction ownership is not proved");
        }
        var factoryRoots = BridgeRootSet.resolve(program, List.of(BridgeCallableId.of(bound.factory())));
        var cleanup = BridgeCleanupAnalyzer.analyze(artifact, factoryRoots, source.type(), Optional.empty());
        if (cleanup.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(cleanup.reason());
        String symbol = "ironwood_bridge_carrier_destroy";
        if (program.functions().stream().anyMatch(function -> function.linkageName().equals(symbol))) {
            throw new IllegalArgumentException("callback carrier destruction symbol collision");
        }
        var span = bound.factory().sourceSpan();
        var receiver = new IrValueReference(0, source.type(), span);
        // Same descriptor destruction used for proved bridge roots, including
        // Throwable's trace cleanup. Only the generated invocation chain may
        // call it, after outer translation has consumed every native alias.
        var destroy = new IrFunction(source.type().referenceName(), "<callback-carrier-destroy>", symbol, IrType.VOID,
                List.of(new IrParameter("carrier", receiver, span)), List.of(new IrBasicBlock("entry",
                List.of(new IrFreeInstruction(receiver, span)), new IrReturnTerminator(Optional.empty(), span), span)),
                span, bound.factory().sourceFileName(), IrCallableKind.METHOD);
        return new BridgeCallbackCarrierCleanup(program, roots, destroy);
    }
}
