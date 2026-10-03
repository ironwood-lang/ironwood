// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.semantic.BridgeCallbackContextLowering;
import ironwood.compiler.semantic.BridgeCallbackReachability;
import ironwood.compiler.semantic.BridgeSynchronousCallbackProof;

import java.util.ArrayList;
import java.util.List;

/** Primitive invocation entries; adapters must still own listener and carrier lifetimes. */
public final class BridgeSynchronousCallbackEntries {
    private final BridgeSynchronousCallbackProof proof;
    private final BridgeCallbackContextLowering.Result context;
    private final BridgeListenerProxyEntries proxies;
    private final List<IrFunction> functions;

    private BridgeSynchronousCallbackEntries(BridgeSynchronousCallbackProof proof, BridgeCallbackContextLowering.Result context,
            BridgeListenerProxyEntries proxies, List<IrFunction> functions) {
        this.proof = proof;
        this.context = context;
        this.proxies = proxies;
        this.functions = List.copyOf(functions);
    }

    public boolean matches(CompilationArtifact artifact, BridgeRootSet roots) { return proof.matches(artifact, roots); }
    public BridgeCallbackContextLowering.Result context() { return context; }
    public BridgeListenerProxyEntries proxies() { return proxies; }
    public List<IrFunction> functions() { return functions; }

    public static BridgeSynchronousCallbackEntries create(CompilationArtifact artifact,
            BridgeListenerProxies listeners, BridgeRootSet requested) {
        var proof = BridgeSynchronousCallbackProof.prove(artifact, listeners, requested);
        var program = artifact.program().orElseThrow();
        var roots = requested.revalidate(program);
        var context = BridgeCallbackContextLowering.lower(program, roots, BridgeCallbackReachability.analyze(program));
        var proxies = BridgeListenerProxyEntries.create(artifact, listeners);
        var functions = new ArrayList<IrFunction>();
        var copied = BridgeCallbackStringEntries.create(artifact, listeners, roots, context).iterator();
        for (var root : roots.roots()) {
            if (root.callable().parameters().contains(ironwood.compiler.ir.IrType.reference("ironwood.lang.String"))) {
                functions.add(copied.next());
                continue;
            }
            var target = BridgeRootSet.resolve(context.program(), List.of(BridgeCallableId.of(context.entries().get(root.callable()))))
                    .roots().getFirst();
            String symbol = "ironwood_bridge_synchronous_callback_" + functions.size();
            if (context.program().functions().stream().anyMatch(function -> function.linkageName().equals(symbol))) {
                throw new IllegalArgumentException("synchronous callback entry symbol collision");
            }
            functions.add(BridgeProtectedEntryLowering.lower(target, symbol, true));
        }
        return new BridgeSynchronousCallbackEntries(proof, context, proxies, functions);
    }
}
