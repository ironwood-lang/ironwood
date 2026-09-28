// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.semantic.BridgeCallbackReachability;
import ironwood.compiler.semantic.BridgeOwnedListenerSlots;

import java.util.List;
import java.util.Optional;

/** Complete native owner composition. Host adapter generation remains a separate producer gate. */
public final class BridgeOwnedCallbackAdmission {
    private final CompilationArtifact artifact;
    private final BridgeExportSurface surface;
    private final BridgeListenerProxies listeners;
    private final BridgeEntryModule storage;
    private final BridgeFinalRootRetention lifetime;
    private final BridgeOwnedCallbackEntries callbacks;
    private final Optional<BridgeOwnedListenerSlots> slots;
    private final BridgeListenerProxyEntries proxies;
    private final BridgeCallbackCarrierEntries carriers;
    private final BridgeCallbackCarrierCleanup cleanup;
    private final BridgeEntryModule entries;
    private final BridgeExceptionClosure.Snapshot exceptions;
    private final IrProgram program;

    private BridgeOwnedCallbackAdmission(CompilationArtifact artifact, BridgeExportSurface surface,
            BridgeListenerProxies listeners, BridgeEntryModule storage, BridgeFinalRootRetention lifetime,
            BridgeOwnedCallbackEntries callbacks, Optional<BridgeOwnedListenerSlots> slots,
            BridgeListenerProxyEntries proxies, BridgeCallbackCarrierEntries carriers, BridgeCallbackCarrierCleanup cleanup,
            BridgeEntryModule entries, BridgeExceptionClosure.Snapshot exceptions, IrProgram program) {
        this.artifact = artifact; this.surface = surface; this.listeners = listeners;
        this.storage = storage; this.lifetime = lifetime; this.callbacks = callbacks; this.slots = slots;
        this.proxies = proxies; this.carriers = carriers; this.cleanup = cleanup;
        this.entries = entries; this.exceptions = exceptions; this.program = program;
    }

    public CompilationArtifact artifact() { return artifact; }
    public BridgeExportSurface surface() { return surface; }
    public BridgeListenerProxies listeners() { return listeners; }
    public BridgeEntryModule storage() { return storage; }
    public BridgeFinalRootRetention lifetime() { return lifetime; }
    public BridgeOwnedCallbackEntries callbacks() { return callbacks; }
    public Optional<BridgeOwnedListenerSlots> slots() { return slots; }
    public BridgeListenerProxyEntries proxies() { return proxies; }
    public BridgeCallbackCarrierEntries carriers() { return carriers; }
    public BridgeCallbackCarrierCleanup cleanup() { return cleanup; }
    public BridgeEntryModule entries() { return entries; }
    public BridgeExceptionClosure.Snapshot exceptions() { return exceptions; }
    public IrProgram program() { return program; }
    public boolean matches(CompilationArtifact candidate, BridgeExportSurface api) {
        return artifact.equals(candidate) && surface.equals(api) && entries.matchesOriginal(candidate)
                && lifetime.matches(storage, lifetime.program());
    }

    public static BridgeProof<BridgeOwnedCallbackAdmission> prove(CompilationArtifact artifact,
            BridgeListenerProxies listeners, BridgeCallbackCarrierSources carrier, List<String> exports) {
        var selected = BridgeExportSurface.ownedCallbacks(artifact, exports);
        if (selected.surface().isEmpty()) return BridgeProof.rejected(selected.diagnostics().toString());
        var surface = selected.surface().orElseThrow();
        try {
            var original = artifact.program().orElseThrow();
            var effects = BridgeCallbackReachability.analyze(original).entries(surface.roots());
            if (effects.values().stream().anyMatch(effect -> !effect.complete())) {
                return BridgeProof.rejected("owner callbacks require complete entry effects");
            }
            var constructorRoots = BridgeRootSet.resolve(original, surface.roots().roots().stream()
                    .filter(root -> root.callable().kind() == IrCallableKind.CONSTRUCTOR).map(BridgeRootSet.Root::callable).toList());
            var callbackRoots = BridgeRootSet.resolve(original, surface.roots().roots().stream()
                    .filter(root -> effects.get(root.callable()).foreign()).map(BridgeRootSet.Root::callable).toList());
            var nativeRoots = BridgeRootSet.resolve(original, surface.roots().roots().stream()
                    .filter(root -> root.callable().kind() != IrCallableKind.CONSTRUCTOR && !effects.get(root.callable()).foreign())
                    .map(BridgeRootSet.Root::callable).toList());
            if (!constructorRoots.resolved() || !callbackRoots.resolved()) {
                return BridgeProof.rejected("owner callback admission requires constructors and callback-bearing entries");
            }
            var storage = BridgeEntryModule.callbackOwnerStorage(artifact, constructorRoots);
            var finalStorage = BridgeFinalRootRetention.prove(artifact, storage);
            if (finalStorage.status() != BridgeProof.Status.PROVED) return BridgeProof.rejected(finalStorage.reason());
            var lifetime = finalStorage.contract().orElseThrow();
            var callbacks = BridgeOwnedCallbackEntries.create(artifact, listeners, callbackRoots, storage, lifetime);
            var slots = nativeRoots.roots().isEmpty() ? Optional.<BridgeOwnedListenerSlots>empty()
                    : Optional.of(BridgeOwnedListenerSlots.prove(artifact, listeners, nativeRoots, storage, lifetime));
            var proxies = BridgeListenerProxyEntries.create(artifact, listeners);
            var carriers = BridgeCallbackCarrierEntries.create(artifact, carrier);
            var cleanup = BridgeCallbackCarrierCleanup.prove(artifact, carrier, callbackRoots);
            var entries = BridgeEntryModule.ownedCallbacks(artifact, surface.roots(), storage, lifetime,
                    callbacks, slots, proxies, carriers, cleanup);
            var closure = BridgeExceptionClosure.callbacks(artifact, entries, carriers);
            if (closure.status() != BridgeProof.Status.PROVED) return BridgeProof.rejected(closure.reason());
            var exceptions = closure.contract().orElseThrow();
            var program = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(exceptions.entries().program()));
            return BridgeProof.proved(new BridgeOwnedCallbackAdmission(artifact, surface, listeners, storage, lifetime,
                    callbacks, slots, proxies, carriers, cleanup, entries, exceptions, program),
                    "complete bounded owner callback native composition; host adapters remain required");
        } catch (IllegalArgumentException failure) {
            return BridgeProof.rejected(failure.getMessage());
        }
    }
}
