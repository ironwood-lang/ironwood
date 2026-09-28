// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrProgram;

import java.util.List;

/** Bound composition for synchronous primitive listeners; listener inputs remain borrowed. */
public final class BridgeCallbackAdmission {
    private final CompilationArtifact artifact;
    private final BridgeExportSurface surface;
    private final BridgeListenerProxies listeners;
    private final BridgeSynchronousCallbackEntries invocations;
    private final BridgeCallbackCarrierEntries carriers;
    private final BridgeCallbackCarrierPolicy cleanup;
    private final BridgeEntryModule entries;
    private final BridgeExceptionClosure.Snapshot exceptions;
    private final IrProgram program;

    private BridgeCallbackAdmission(CompilationArtifact artifact, BridgeExportSurface surface,
            BridgeListenerProxies listeners, BridgeSynchronousCallbackEntries invocations,
            BridgeCallbackCarrierEntries carriers, BridgeCallbackCarrierPolicy cleanup,
            BridgeEntryModule entries, BridgeExceptionClosure.Snapshot exceptions, IrProgram program) {
        this.artifact = artifact;
        this.surface = surface;
        this.listeners = listeners;
        this.invocations = invocations;
        this.carriers = carriers;
        this.cleanup = cleanup;
        this.entries = entries;
        this.exceptions = exceptions;
        this.program = program;
    }

    public CompilationArtifact artifact() { return artifact; }
    public BridgeExportSurface surface() { return surface; }
    public BridgeListenerProxies listeners() { return listeners; }
    public BridgeSynchronousCallbackEntries invocations() { return invocations; }
    public BridgeCallbackCarrierEntries carriers() { return carriers; }
    public BridgeCallbackCarrierPolicy cleanup() { return cleanup; }
    public BridgeEntryModule entries() { return entries; }
    public BridgeExceptionClosure.Snapshot exceptions() { return exceptions; }
    public IrProgram program() { return program; }
    public boolean matches(CompilationArtifact candidate, BridgeExportSurface api) {
        return artifact.equals(candidate) && surface.equals(api) && invocations.matches(candidate, api.roots())
                && cleanup.matches(candidate, api.roots()) && carriers.matches(candidate);
    }

    public static BridgeProof<BridgeCallbackAdmission> prove(CompilationArtifact artifact,
            BridgeListenerProxies listeners, BridgeCallbackCarrierSources carrier, List<String> exports) {
        var selected = BridgeExportSurface.synchronousCallbacks(artifact, exports);
        if (selected.surface().isEmpty()) return BridgeProof.rejected(selected.diagnostics().toString());
        var surface = selected.surface().orElseThrow();
        try {
            var invocations = BridgeSynchronousCallbackEntries.create(artifact, listeners, surface.roots());
            var carriers = BridgeCallbackCarrierEntries.create(artifact, carrier);
            var cleanup = BridgeCallbackCarrierPolicy.prove(artifact, carrier, surface.roots());
            var entries = BridgeEntryModule.synchronousCallbacks(artifact, surface.roots(), invocations, carriers, cleanup);
            var closure = BridgeExceptionClosure.callbacks(artifact, entries, carriers);
            if (closure.status() != BridgeProof.Status.PROVED) return BridgeProof.rejected(closure.reason());
            var exceptions = closure.contract().orElseThrow();
            var program = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(exceptions.entries().program()));
            return BridgeProof.proved(new BridgeCallbackAdmission(artifact, surface, listeners, invocations, carriers,
                    cleanup, entries, exceptions, program), "complete synchronous primitive callback composition");
        } catch (IllegalArgumentException failure) {
            return BridgeProof.rejected(failure.getMessage());
        }
    }
}
