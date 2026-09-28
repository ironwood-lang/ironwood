// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.semantic.BridgeCallbackCarrierLifetime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Per-entry D227 policy. Retention never substitutes for a destruction proof. */
public final class BridgeCallbackCarrierPolicy {
    private final IrProgram program;
    private final BridgeRootSet roots;
    private final BridgeRootSet reclaimable;
    private final Map<BridgeCallableId, BridgeCallbackCarrierLifetime> lifetimes;
    private final Optional<BridgeCallbackCarrierCleanup> cleanup;

    private BridgeCallbackCarrierPolicy(IrProgram program, BridgeRootSet roots, BridgeRootSet reclaimable,
            Map<BridgeCallableId, BridgeCallbackCarrierLifetime> lifetimes, Optional<BridgeCallbackCarrierCleanup> cleanup) {
        this.program = program; this.roots = roots; this.reclaimable = reclaimable;
        this.lifetimes = Map.copyOf(lifetimes); this.cleanup = cleanup;
    }

    public boolean matches(CompilationArtifact artifact, BridgeRootSet requested) {
        return artifact.valid() && artifact.program().filter(program::equals).isPresent()
                && roots.equals(requested.revalidate(program));
    }

    public boolean reclaims(BridgeCallableId callable) {
        var lifetime = lifetimes.get(callable);
        if (lifetime == null) throw new IllegalArgumentException("entry is absent from carrier policy");
        return lifetime.invocationOwned();
    }

    public BridgeRootSet reclaimableRoots() { return reclaimable; }
    public Optional<BridgeCallbackCarrierCleanup> cleanup() { return cleanup; }

    public static BridgeCallbackCarrierPolicy prove(CompilationArtifact artifact,
            BridgeCallbackCarrierSources source, BridgeRootSet requested) {
        source.bind(artifact);
        var program = artifact.program().orElseThrow();
        var roots = requested.revalidate(program);
        if (!roots.resolved()) throw new IllegalArgumentException("carrier policy requires resolved roots");
        var lifetimes = new java.util.LinkedHashMap<BridgeCallableId, BridgeCallbackCarrierLifetime>();
        var owned = new ArrayList<BridgeCallableId>();
        for (var root : roots.roots()) {
            var lifetime = BridgeCallbackCarrierLifetime.analyze(program, BridgeRootSet.resolve(program, List.of(root.callable())));
            lifetimes.put(root.callable(), lifetime);
            if (lifetime.invocationOwned()) owned.add(root.callable());
        }
        var reclaimable = BridgeRootSet.resolve(program, owned);
        var cleanup = owned.isEmpty() ? Optional.<BridgeCallbackCarrierCleanup>empty()
                : Optional.of(BridgeCallbackCarrierCleanup.prove(artifact, source, reclaimable));
        return new BridgeCallbackCarrierPolicy(program, roots, reclaimable, lifetimes, cleanup);
    }
}
