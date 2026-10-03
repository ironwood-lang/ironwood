// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrProgram;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Protected compiler-owned carrier operations, without export or destruction permission. */
public final class BridgeCallbackCarrierEntries {
    private final IrProgram original;
    private final List<IrFunction> functions;

    private BridgeCallbackCarrierEntries(IrProgram original, List<IrFunction> functions) {
        this.original = original;
        this.functions = List.copyOf(functions);
    }

    public IrFunction factory() { return functions.get(0); }
    public IrFunction reference() { return functions.get(1); }
    public IrFunction next() { return functions.get(2); }
    public IrFunction exhausted() { return functions.get(3); }
    public IrFunction unchangedReference() { return functions.get(4); }
    public IrFunction cause() { return functions.get(5); }
    public IrFunction secondaryCount() { return functions.get(6); }
    public IrFunction secondary() { return functions.get(7); }
    public List<IrFunction> functions() { return functions; }
    public boolean matches(CompilationArtifact artifact) {
        return artifact.valid() && artifact.program().filter(original::equals).isPresent();
    }

    public static BridgeCallbackCarrierEntries create(CompilationArtifact artifact, BridgeCallbackCarrierSources source) {
        var bound = source.bind(artifact);
        var program = artifact.program().orElseThrow();
        if (program.allocationFailure().isEmpty()) {
            throw new IllegalArgumentException("callback carrier requires native allocation failure context");
        }
        List<IrFunction> functions = new ArrayList<>();
        for (var operation : List.of(bound.factory(), bound.reference(), bound.next(), bound.exhausted(),
                bound.unchangedReference(), bound.cause(), bound.secondaryCount(), bound.secondary())) {
            String symbol = "ironwood_bridge_carrier_" + operation.sourceName();
            if (program.functions().stream().anyMatch(function -> function.linkageName().equals(symbol))) {
                throw new IllegalArgumentException("callback carrier entry symbol collision: " + symbol);
            }
            var roots = BridgeRootSet.resolve(program, List.of(BridgeCallableId.of(operation)));
            if (!roots.resolved()) throw new IllegalArgumentException("unresolved callback carrier operation");
            functions.add(BridgeProtectedEntryLowering.lower(roots.roots().getFirst(), symbol, true));
        }
        return new BridgeCallbackCarrierEntries(program, functions);
    }

    public IrProgram program() {
        var all = new ArrayList<>(original.functions());
        all.addAll(functions);
        return new IrProgram(original.moduleName(), original.classes(), original.staticFields(), original.typeInitializations(),
                original.arrayTypes(), original.stringConstants(), original.dispatchSlots(), all, Optional.empty(),
                original.allocationFailure(), original.exportRoots());
    }
}
