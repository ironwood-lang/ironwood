// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeCallTargets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Managed protected-entry lowering with exact roots and JNI completion. */
public final class BridgeEntryModule {
    /** Fixed inputs use source-callable indices, including the instance receiver. */
    public record Entry(BridgeRootSet.Root root, IrFunction function, Map<Integer, Integer> fixedEnums) {
        public Entry { fixedEnums = Map.copyOf(fixedEnums); }
        public Entry(BridgeRootSet.Root root, IrFunction function) { this(root, function, Map.of()); }
    }

    private final IrProgram program;
    private final Optional<IrProgram> callbackOriginal;
    private final Optional<BridgeGcCallbacks> gcCallbacks;
    private final List<Entry> entries;
    private final Optional<BridgeRootRetentionContract> rootRetention;

    private final Optional<BridgeEnumConversions> enumConversions;

    private BridgeEntryModule(IrProgram program, List<Entry> entries,
            Optional<BridgeRootRetentionContract> rootRetention,
            Optional<BridgeEnumConversions> enumConversions) {
        this.callbackOriginal = Optional.empty();
        this.gcCallbacks = Optional.empty();
        this.entries = List.copyOf(entries);
        this.rootRetention = rootRetention;
        this.enumConversions = enumConversions;
        this.program = new IrProgram(program.moduleName(), program.classes(), program.staticFields(),
                program.typeInitializations(), program.arrayTypes(), program.stringConstants(), program.dispatchSlots(),
                program.functions(), program.entryPoint(), program.allocationFailure(), entrySymbols());
    }

    public IrProgram program() { return program; }
    public Optional<BridgeGcCallbacks> gcCallbacks() { return this.gcCallbacks; }
    public List<Entry> entries() { return entries; }
    public List<Entry> primaryEntries() { return entries.stream().filter(entry -> entry.fixedEnums().isEmpty()).toList(); }
    public Optional<BridgeRootRetentionContract> rootRetention() { return rootRetention; }

    public Optional<BridgeEnumConversions> enumConversions() { return enumConversions; }

    /** GC storage has no managed dependency ledger or destruction capabilities.
     * Foreign descriptor confinement remains a distinct admission requirement.
     */
    public static BridgeEntryModule managed(CompilationArtifact artifact, BridgeExportSurface surface) {
        return managed(artifact, surface, Optional.empty());
    }

    public static BridgeEntryModule managed(CompilationArtifact artifact, BridgeExportSurface surface, Optional<BridgeGcCallbacks> callbacks) {
        if (!artifact.valid() || artifact.program().isEmpty()) throw new IllegalArgumentException("managed bridge requires valid source");
        var original = artifact.program().orElseThrow();
        if (callbacks.isEmpty() && surface.types().stream().anyMatch(type -> type.kind() == ironwood.compiler.semantic.BridgeApiFacts.Kind.INTERFACE))
            throw new IllegalArgumentException("managed listeners require exact proxy and JNI completion capabilities");
        var base = callbacks.map(value -> value.context().program()).orElse(original);
        var roots = surface.roots().revalidate(original);
        if (!roots.resolved()) throw new IllegalArgumentException("unresolved managed bridge roots");
        var mappings = BridgeEnumConversions.forManagedSurface(artifact, surface, callbacks.map(BridgeGcCallbacks::listeners).orElse(null));
        var rootTypes = surface.types().stream().filter(type -> type.kind() == ironwood.compiler.semantic.BridgeApiFacts.Kind.CLASS
                && !type.throwable()).map(type -> IrType.reference(type.binaryName())).collect(Collectors.toSet());
        var retention = new java.util.LinkedHashMap<BridgeCallableId, BridgeRetentionContract>();
        var origins = new java.util.LinkedHashMap<BridgeCallableId, BridgeResultOriginContract>();
        var generated = new ArrayList<Entry>();
        var functions = new ArrayList<>(base.functions());
        callbacks.ifPresent(value -> functions.addAll(value.support()));
        var calls = new BridgeCallTargets(original);
        for (var root : roots.roots()) {
            var id = root.callable();
            if (id.parameters().stream().anyMatch(ironwood.compiler.semantic.BridgeByteViews::view)) {
                var proof = ironwood.compiler.semantic.BridgeByteViews.analyzeManaged(artifact, id,
                        callbacks.map(BridgeGcCallbacks::listeners).orElse(null));
                if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
            }
            var slots = callbacks.map(value -> value.slots().get(id)).orElse(new BridgeRetentionContract(List.of()));
            retention.put(id, slots);
            var origin = artifact.bridgeConstructionFacts().orElseThrow().resultOrigins().get(id);
            if (origin != null && origin.status() == BridgeProof.Status.PROVED) origins.put(id, origin.contract().orElseThrow());
            var initialization = calls.initializers(id.owner());
            if (!initialization.complete()) throw new IllegalArgumentException("unresolved bridge initialization");
            var target = callbacks.map(value -> value.context().entries().get(id)).orElseGet(() -> original.functions().stream()
                    .filter(value -> value.linkageName().equals(id.linkage())).findFirst().orElseThrow());
            var targetRoot = BridgeRootSet.resolve(base, List.of(BridgeCallableId.of(target))).roots().getFirst();
            var function = BridgeRootEntryLowering.lower(targetRoot, "ironwood_bridge_entry_" + generated.size(), slots,
                    !initialization.targets().isEmpty(),
                    mappings.parameters().getOrDefault(id, List.of()), Optional.ofNullable(mappings.results().get(id)), Map.of());
            functions.add(function);
            generated.add(new Entry(root, function));
        }
        var protocol = new BridgeRootRetentionContract(original, roots, rootTypes, retention, origins);
        var module = new BridgeEntryModule(new IrProgram(base.moduleName(), base.classes(), base.staticFields(), base.typeInitializations(),
                base.arrayTypes(), base.stringConstants(), base.dispatchSlots(), functions, Optional.empty(), base.allocationFailure()),
                generated, Optional.of(protocol), Optional.of(mappings));
        return callbacks.isEmpty() ? module : new BridgeEntryModule(module, callbacks.orElseThrow().support().stream()
                .map(IrFunction::linkageName).collect(Collectors.toSet()), original, callbacks);
    }
    public Set<String> entrySymbols() {
        return entries.stream().map(Entry::function)
                .map(IrFunction::linkageName).collect(Collectors.toUnmodifiableSet());
    }

    /** Exact original identity, including proof-bound callback dispatch specialization. */
    public boolean matchesOriginal(CompilationArtifact artifact) {
        if (!artifact.valid() || artifact.program().isEmpty()) return false;
        var original = artifact.program().orElseThrow();
        if (callbackOriginal.isPresent()) return callbackOriginal.orElseThrow().equals(original);
        var restored = new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(),
                program.arrayTypes(), program.stringConstants(), program.dispatchSlots(), original.functions(), original.entryPoint(),
                program.allocationFailure(), original.exportRoots());
        return restored.equals(original) && program.functions().containsAll(original.functions());
    }

    /** Composition requires independent final storage, callback and callback-free slot proofs. */

    private BridgeEntryModule(BridgeEntryModule module, Set<String> support, IrProgram original) {
        this(module, support, original, module.gcCallbacks);
    }

    private BridgeEntryModule(BridgeEntryModule module, Set<String> support, IrProgram original, Optional<BridgeGcCallbacks> callbacks) {
        callbackOriginal = Optional.of(original);
        this.gcCallbacks = callbacks;
        entries = module.entries;
        rootRetention = module.rootRetention;
        enumConversions = module.enumConversions;
        var base = module.program;
        var exports = new java.util.LinkedHashSet<>(base.exportRoots());
        exports.addAll(support);
        program = new IrProgram(base.moduleName(), base.classes(), base.staticFields(), base.typeInitializations(),
                base.arrayTypes(), base.stringConstants(), base.dispatchSlots(), base.functions(), base.entryPoint(),
                base.allocationFailure(), exports);
    }

    /** Named enum inputs and scalar results, with conversion and dispatch proofs. */

}
