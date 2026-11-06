// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

import java.util.List;
import java.util.Optional;

/** Complete internal object admission. A final native proof does not supply the pending host adapters. */
public final class BridgeObjectAdmission {

    private final CompilationArtifact artifact;
    private final BridgeExportSurface surface;
    private final BridgeEntryModule entries;
    private final BridgeManagedImage lifetime;
    private final Optional<BridgeFinalRootRetention> roots;

    private final Optional<BridgeCallbackBatching> batching;

    private BridgeObjectAdmission(CompilationArtifact artifact, BridgeExportSurface surface, BridgeEntryModule entries,
            BridgeManagedImage lifetime, Optional<BridgeFinalRootRetention> roots) {
        this.artifact = artifact;
        this.surface = surface;
        this.entries = entries;
        this.lifetime = lifetime;
        this.roots = roots;
        this.batching = entries.gcCallbacks().isPresent()
                ? Optional.of(BridgeCallbackBatching.proveManaged(this)) : Optional.empty();
    }

    public BridgeExportSurface surface() { return surface; }
    public BridgeEntryModule entries() { return entries; }
    public BridgeManagedImage lifetime() { return lifetime; }
    public Optional<BridgeFinalRootRetention> roots() { return roots; }

    public IrProgram program() { return lifetime.program(); }

    public Optional<BridgeCallbackBatching> batching() { return batching; }
    public IrProgram emissionProgram() { return batching.map(BridgeCallbackBatching::program).orElseGet(this::program); }

    public static BridgeProof<BridgeObjectAdmission> proveManaged(CompilationArtifact artifact, List<String> exports) {
        return proveManaged(artifact, exports, null, null);
    }

    public static BridgeProof<BridgeObjectAdmission> proveManaged(CompilationArtifact artifact, List<String> exports,
            BridgeListenerProxies listeners, BridgeCallbackCarrierSources carrier) {
        var selected = BridgeExportSurface.managedObjects(artifact, exports, listeners);
        if (selected.surface().isEmpty()) return BridgeProof.rejected(selected.diagnostics().toString());
        var surface = selected.surface().orElseThrow();
        try {
            var callbacks = listeners == null ? Optional.<BridgeGcCallbacks>empty()
                    : Optional.of(BridgeGcCallbacks.prove(artifact, surface, listeners, java.util.Objects.requireNonNull(carrier)));
            var module = BridgeEntryModule.managed(artifact, surface, callbacks);
            var lifetime = BridgeManagedImage.managed(artifact, module, surface);
            var roots = BridgeFinalRootRetention.managed(module, lifetime);
            return BridgeProof.proved(new BridgeObjectAdmission(artifact, surface, module, lifetime, Optional.of(roots)),
                    "managed bridge source, typed entries and final heap invariant agree");
        } catch (IllegalArgumentException failure) { return BridgeProof.rejected(failure.getMessage()); }
    }
    public boolean matches(CompilationArtifact candidate, BridgeExportSurface api) {
        return artifact.equals(candidate) && surface.equals(api) && lifetime.matches(entries, program());
    }

}
