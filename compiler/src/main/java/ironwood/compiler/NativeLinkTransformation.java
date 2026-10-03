// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrProgram;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact provenance of the fixed, semantics-preserving post-validation passes. */
public final class NativeLinkTransformation {
    private final IrProgram input;
    private final IrProgram program;
    private final Map<BridgeCallableId, BridgeCallableId> origins;

    private NativeLinkTransformation(IrProgram input, IrProgram program, Map<BridgeCallableId, BridgeCallableId> origins) {
        this.input = input;
        this.program = program;
        this.origins = Map.copyOf(origins);
    }

    /** Execute the real passes; callers cannot attest an independently modified output. */
    public static NativeLinkTransformation apply(IrProgram input) {
        Map<String, BridgeCallableId> sources = new LinkedHashMap<>();
        for (var function : input.functions()) {
            if (sources.putIfAbsent(function.linkageName(), BridgeCallableId.of(function)) != null) {
                throw new IllegalArgumentException("native transformation requires unique input symbols");
            }
        }
        var optimized = NativeLinkPipeline.optimize(input, (original, copy) -> {
            var source = sources.get(original.linkageName());
            if (source == null || sources.putIfAbsent(copy.linkageName(), source) != null) {
                throw new IllegalArgumentException("native transformation has an unresolved or colliding clone origin");
            }
        });
        var program = NativeLinkPipeline.finish(optimized);
        Map<BridgeCallableId, BridgeCallableId> origins = new LinkedHashMap<>();
        for (IrFunction function : program.functions()) {
            var id = BridgeCallableId.of(function);
            var source = sources.get(function.linkageName());
            if (source == null || !id.owner().equals(source.owner()) || id.kind() != source.kind()
                    || !id.parameters().equals(source.parameters()) || !id.result().equals(source.result())
                    || origins.putIfAbsent(id, source) != null) {
                throw new IllegalArgumentException("native transformation changed a callable contract without provenance");
            }
        }
        if (!input.exportRoots().equals(program.exportRoots())) {
            throw new IllegalArgumentException("native transformation changed explicit export roots");
        }
        return new NativeLinkTransformation(input, program, origins);
    }

    public boolean startsWith(IrProgram candidate) { return input.equals(candidate); }
    public IrProgram program() { return program; }
    public Map<BridgeCallableId, BridgeCallableId> origins() { return origins; }
}
