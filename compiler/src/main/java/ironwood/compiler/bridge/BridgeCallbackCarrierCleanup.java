// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.semantic.BridgeCallbackCarrierLifetime;

/** Invocation-bound JNI completion, independent of managed carrier reclamation. */
public final class BridgeCallbackCarrierCleanup {
    private final IrProgram program;
    private final BridgeRootSet roots;

    private BridgeCallbackCarrierCleanup(IrProgram program, BridgeRootSet roots) {
        this.program = program;
        this.roots = roots;
    }

    public boolean matches(CompilationArtifact artifact, BridgeRootSet requested) {
        return artifact.valid() && artifact.program().filter(program::equals).isPresent()
                && roots.equals(requested.revalidate(program));
    }

    public static BridgeCallbackCarrierCleanup prove(CompilationArtifact artifact,
            BridgeCallbackCarrierSources source, BridgeRootSet requested) {
        source.bind(artifact);
        var program = artifact.program().orElseThrow();
        var roots = requested.revalidate(program);
        var lifetime = BridgeCallbackCarrierLifetime.analyze(program, roots);
        if (!lifetime.invocationOwned()) {
            throw new IllegalArgumentException("callback carrier may outlive invocation: " + lifetime.retentionReasons());
        }
        // Exact compiler-owned declaration plus complete invocation-lifetime
        // proof supplies JNI authority. No heap ownership/destruction proof is
        // required, and no legacy managed-deallocation IR may be generated.
        return new BridgeCallbackCarrierCleanup(program, roots);
    }
}
