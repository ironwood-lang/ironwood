// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.semantic.BridgeConstructionFacts;

/** Final exact managed image, exception projection and input identity. */
public final class BridgeManagedImage {
    private final BridgeEntryModule module;
    private final BridgeExceptionClosure.Snapshot exceptions;
    private final IrProgram program;

    private final BridgeConstructionFacts constructionFacts;

    private BridgeManagedImage(BridgeEntryModule module, BridgeExceptionClosure.Snapshot exceptions,
            IrProgram program, BridgeConstructionFacts constructionFacts) {
        this.module = module;
        this.exceptions = exceptions;
        this.program = program;
        this.constructionFacts = constructionFacts;
    }

    public IrProgram program() { return program; }
    public BridgeExceptionClosure.Snapshot exceptions() { return exceptions; }

    public BridgeConstructionFacts constructionFacts() { return constructionFacts; }
    public boolean matches(BridgeEntryModule entries, IrProgram candidate) { return module == entries && program.equals(candidate); }

    static BridgeManagedImage managed(CompilationArtifact artifact, BridgeEntryModule module, BridgeExportSurface surface) {
        var closure = BridgeExceptionClosure.managedSnapshots(artifact, module, surface);
        if (closure.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(closure.reason());
        var exceptions = closure.contract().orElseThrow();
        var transformation = NativeLinkTransformation.apply(exceptions.entries().program(), true);
        var program = transformation.program();
        ironwood.compiler.backend.GcManagedHeapVerifier.verifyBridge(program);
        var facts = artifact.bridgeConstructionFacts().orElseThrow().forManagedBridge(artifact, module, exceptions.entries()).afterNativeLink(transformation);
        return new BridgeManagedImage(module, exceptions, program, facts);
    }

}
