// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeFinalRootRetention;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeCallbackContextLowering;
import ironwood.compiler.semantic.BridgeCallbackReachability;
import ironwood.compiler.semantic.BridgeOwnedCallbackProof;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Protected owner calls and their exact Java guard obligations; no public surface permission. */
public final class BridgeOwnedCallbackEntries {
    public record Entry(BridgeCallableId callable, IrFunction function, List<Integer> guardedInputs) {
        public Entry { guardedInputs = List.copyOf(guardedInputs); }
    }

    private final BridgeRootSet roots;
    private final BridgeEntryModule storage;
    private final BridgeFinalRootRetention lifetime;
    private final BridgeOwnedCallbackProof proof;
    private final BridgeCallbackContextLowering.Result context;
    private final List<Entry> entries;

    private BridgeOwnedCallbackEntries(BridgeRootSet roots, BridgeEntryModule storage, BridgeFinalRootRetention lifetime,
            BridgeOwnedCallbackProof proof, BridgeCallbackContextLowering.Result context, List<Entry> entries) {
        this.roots = roots;
        this.storage = storage;
        this.lifetime = lifetime;
        this.proof = proof;
        this.context = context;
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() { return entries; }
    public BridgeCallbackContextLowering.Result context() { return context; }
    public boolean matches(CompilationArtifact artifact, BridgeRootSet requested) {
        return proof.matches(artifact, requested, storage, lifetime);
    }
    public boolean matches(CompilationArtifact artifact, BridgeRootSet requested,
            BridgeEntryModule requestedStorage, BridgeFinalRootRetention requestedLifetime) {
        return proof.matches(artifact, requested, requestedStorage, requestedLifetime);
    }

    public static BridgeOwnedCallbackEntries create(CompilationArtifact artifact, BridgeListenerProxies listeners,
            BridgeRootSet requested, BridgeEntryModule storage, BridgeFinalRootRetention lifetime) {
        var program = artifact.program().orElseThrow();
        var context = BridgeCallbackContextLowering.lower(program, requested, BridgeCallbackReachability.analyze(program));
        return create(artifact, listeners, requested, storage, lifetime, requested, context);
    }

    /** Other roots may share specialization, but need their own invocation and ownership proofs. */
    public static BridgeOwnedCallbackEntries create(CompilationArtifact artifact, BridgeListenerProxies listeners,
            BridgeRootSet requested, BridgeEntryModule storage, BridgeFinalRootRetention lifetime,
            BridgeRootSet contextRoots, BridgeCallbackContextLowering.Result context) {
        var proof = BridgeOwnedCallbackProof.prove(artifact, listeners, requested, storage, lifetime);
        var program = artifact.program().orElseThrow();
        var roots = requested.revalidate(program);
        var expected = BridgeCallbackContextLowering.lower(program, contextRoots, BridgeCallbackReachability.analyze(program));
        if (!expected.equals(context) || roots.roots().stream().anyMatch(root -> !context.entries().containsKey(root.callable()))) {
            throw new IllegalArgumentException("owned callback entry requires exact context specialization covering its roots");
        }
        var entries = new ArrayList<Entry>();
        for (var root : roots.roots()) {
            if (root.callable().parameters().contains(IrType.reference("ironwood.lang.String"))) {
                throw new IllegalArgumentException("owned callback entries require separate copied String cleanup integration");
            }
            var target = BridgeRootSet.resolve(context.program(), List.of(BridgeCallableId.of(context.entries().get(root.callable()))))
                    .roots().getFirst();
            String symbol = "ironwood_bridge_owned_callback_" + entries.size();
            if (context.program().functions().stream().anyMatch(function -> function.linkageName().equals(symbol))) {
                throw new IllegalArgumentException("owned callback entry symbol collision");
            }
            entries.add(new Entry(root.callable(), BridgeProtectedEntryLowering.lower(target, symbol, true),
                    proof.guardedInputs().get(root.callable())));
        }
        return new BridgeOwnedCallbackEntries(roots, storage, lifetime, proof, context, entries);
    }

    /** Map only the proved input indices to already evaluated, stable owning-root locals. */
    public String guard(CompilationArtifact artifact, BridgeCallableId callable, Map<Integer, String> ownerLocals, String body) {
        if (!matches(artifact, roots)) throw new IllegalArgumentException("callback guards require matching owner entries");
        var entry = entries.stream().filter(value -> value.callable().equals(callable)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("callback guard has no proved invocation"));
        if (!ownerLocals.keySet().equals(Set.copyOf(entry.guardedInputs()))) {
            throw new IllegalArgumentException("callback guard must cover every proved owner input exactly");
        }
        return BridgeCallbackGuardSources.wrap(body, entry.guardedInputs().stream().map(ownerLocals::get).toList());
    }
}
