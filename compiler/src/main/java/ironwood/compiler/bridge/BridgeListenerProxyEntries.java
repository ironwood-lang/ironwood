// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrProgram;

import java.util.List;

/** Construction and cleanup capabilities, without permission to reclaim a published listener. */
public final class BridgeListenerProxyEntries {
    public record Operations(String listener, IrFunction create, IrFunction destroy) {}

    private final IrProgram original;
    private final List<Operations> operations;

    private BridgeListenerProxyEntries(IrProgram original, List<Operations> operations) {
        this.original = original;
        this.operations = List.copyOf(operations);
    }

    public List<Operations> operations() { return operations; }
    public List<IrFunction> functions() {
        return operations.stream().flatMap(operation -> java.util.stream.Stream.of(operation.create(), operation.destroy())).toList();
    }
    public boolean matches(CompilationArtifact artifact) {
        return artifact.valid() && artifact.program().filter(original::equals).isPresent();
    }

    public static BridgeListenerProxyEntries create(CompilationArtifact artifact, BridgeListenerProxies proxies) {
        proxies.validateArtifact(artifact);
        var program = artifact.program().orElseThrow();
        var constructors = proxies.proxies().stream().map(proxy -> {
            var found = program.functions().stream().filter(function -> function.ownerClass().equals(proxy.binaryName())
                    && function.constructor()).toList();
            if (found.size() != 1) throw new IllegalArgumentException("listener proxy requires one exact constructor");
            return BridgeCallableId.of(found.getFirst());
        }).toList();
        // Reuse the P0/P3 constructor confinement, rollback, non-reclamation and
        // descriptor cleanup proofs. These roots do not include listener uses;
        // their publication/active lifetime must be established separately.
        var entries = BridgeEntryModule.rootObjects(artifact, BridgeRootSet.resolve(program, constructors));
        var operations = proxies.proxies().stream().map(proxy -> new Operations(proxy.listener().binaryName(),
                entries.entries().stream().filter(entry -> entry.root().callable().owner().equals(proxy.binaryName()))
                        .findFirst().orElseThrow().function(),
                entries.destructions().stream().filter(entry -> entry.contract().type().referenceName().equals(proxy.binaryName()))
                        .findFirst().orElseThrow().function())).toList();
        return new BridgeListenerProxyEntries(program, operations);
    }
}
