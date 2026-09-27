// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.semantic.BridgeCallTargets;
import ironwood.compiler.semantic.BridgeRootRetentionAnalyzer;
import ironwood.compiler.semantic.BridgeDestructionAnalyzer;
import ironwood.compiler.semantic.BridgePermanentAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Internal protected-entry lowering shared by the P0 harness and later producer. */
public final class BridgeEntryModule {
    public record Entry(BridgeRootSet.Root root, IrFunction function) {}
    public record Destruction(BridgeDestructionContract contract, IrFunction function) {}

    private final IrProgram program;
    private final List<Entry> entries;
    private final Optional<BridgeRootRetentionContract> rootRetention;
    private final List<Destruction> destructions;
    private final Optional<BridgePermanentContract> permanent;
    private final Map<BridgeCallableId, BridgeStringResultContract> stringResults;

    private BridgeEntryModule(IrProgram program, List<Entry> entries) {
        this(program, entries, Optional.empty(), List.of());
    }

    private BridgeEntryModule(IrProgram program, List<Entry> entries,
            Optional<BridgeRootRetentionContract> rootRetention, List<Destruction> destructions) {
        this(program, entries, rootRetention, destructions, Optional.empty());
    }

    private BridgeEntryModule(IrProgram program, List<Entry> entries,
            Optional<BridgeRootRetentionContract> rootRetention, List<Destruction> destructions,
            Optional<BridgePermanentContract> permanent) {
        this(program, entries, rootRetention, destructions, permanent, Map.of());
    }

    private BridgeEntryModule(IrProgram program, List<Entry> entries,
            Optional<BridgeRootRetentionContract> rootRetention, List<Destruction> destructions,
            Optional<BridgePermanentContract> permanent,
            Map<BridgeCallableId, BridgeStringResultContract> stringResults) {
        this.entries = List.copyOf(entries);
        this.rootRetention = rootRetention;
        this.destructions = List.copyOf(destructions);
        this.permanent = permanent;
        this.stringResults = Map.copyOf(stringResults);
        this.program = new IrProgram(program.moduleName(), program.classes(), program.staticFields(),
                program.typeInitializations(), program.arrayTypes(), program.stringConstants(), program.dispatchSlots(),
                program.functions(), program.entryPoint(), program.allocationFailure(), entrySymbols());
    }

    public IrProgram program() { return program; }
    public List<Entry> entries() { return entries; }
    public Optional<BridgeRootRetentionContract> rootRetention() { return rootRetention; }
    public List<Destruction> destructions() { return destructions; }
    public Optional<BridgePermanentContract> permanent() { return permanent; }
    public Map<BridgeCallableId, BridgeStringResultContract> stringResults() { return stringResults; }
    public Set<String> entrySymbols() {
        return java.util.stream.Stream.concat(entries.stream().map(Entry::function), destructions.stream().map(Destruction::function))
                .map(IrFunction::linkageName).collect(Collectors.toUnmodifiableSet());
    }

    /** Uniform permanent storage, including proved unpublished constructor rollback. */
    public static BridgeEntryModule permanentObjects(CompilationArtifact artifact, BridgeRootSet requested) {
        var proof = BridgePermanentAnalyzer.analyze(artifact, requested);
        if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
        var contract = proof.contract().orElseThrow();
        var original = contract.program();
        if (original.allocationFailure().isEmpty()) throw new IllegalArgumentException("permanent entry requires allocation failure context");
        Map<BridgeCallableId, BridgeStringResultContract> results = new java.util.LinkedHashMap<>();
        BridgeStringResults.proveForPermanent(artifact, contract.roots(), contract).forEach((id, result) -> {
            if (result.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(result.reason());
            results.put(id, result.contract().orElseThrow());
        });
        List<Entry> entries = new ArrayList<>();
        List<IrFunction> functions = new ArrayList<>(original.functions());
        var targets = new BridgeCallTargets(original);
        for (var root : contract.roots().roots()) {
            String symbol = "ironwood_bridge_entry_" + entries.size();
            if (functions.stream().anyMatch(function -> function.linkageName().equals(symbol))) {
                throw new IllegalArgumentException("generated bridge symbol collision: " + symbol);
            }
            var initialization = targets.initializers(root.callable().owner());
            if (!initialization.complete()) throw new IllegalArgumentException("incomplete permanent entry initialization");
            // Every reference input/result is non-reclaimable. Native publication
            // needs no Java incoming-count delta; this is not a retention exemption
            // for a mixed permanent/reclaimable export surface.
            var function = BridgeRootEntryLowering.lower(root, symbol, new BridgeRetentionContract(List.of()),
                    !initialization.targets().isEmpty(), Optional.ofNullable(results.get(root.callable())));
            functions.add(function);
            entries.add(new Entry(root, function));
        }
        return new BridgeEntryModule(new IrProgram(original.moduleName(), original.classes(), original.staticFields(),
                original.typeInitializations(), original.arrayTypes(), original.stringConstants(), original.dispatchSlots(),
                functions, Optional.empty(), original.allocationFailure()), entries, Optional.empty(), List.of(), Optional.of(contract), results);
    }

    /** Bounded constructed roots, proved uniform root results and exact slot payloads. */
    public static BridgeEntryModule rootObjects(CompilationArtifact artifact, BridgeRootSet requested) {
        var admitted = BridgeRootRetentionAnalyzer.analyze(artifact, requested);
        if (admitted.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(admitted.reason());
        var contract = admitted.contract().orElseThrow();
        var original = contract.program();
        if (original.allocationFailure().isEmpty()) throw new IllegalArgumentException("root creation requires allocation failure context");
        Map<BridgeCallableId, BridgeStringResultContract> results = new java.util.LinkedHashMap<>();
        if (contract.roots().roots().stream().anyMatch(root -> root.callable().result().equals(IrType.reference("ironwood.lang.String")))) {
            BridgeStringResults.proveForRoots(artifact, contract.roots(), contract).forEach((id, proof) -> {
                if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
                results.put(id, proof.contract().orElseThrow());
            });
        }
        List<Entry> entries = new ArrayList<>();
        for (var root : contract.roots().roots()) {
            if (root.callable().kind() == IrCallableKind.CONSTRUCTOR) {
                var rollback = BridgeDestructionAnalyzer.rollback(artifact, contract.roots(), root.callable());
                if (rollback.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(rollback.reason());
            }
            String symbol = "ironwood_bridge_entry_" + entries.size();
            var initialization = new BridgeCallTargets(original).initializers(root.callable().owner());
            if (!initialization.complete()) throw new IllegalArgumentException("incomplete root entry initialization");
            entries.add(new Entry(root, BridgeRootEntryLowering.lower(root, symbol, contract.entries().get(root.callable()),
                    !initialization.targets().isEmpty(), Optional.ofNullable(results.get(root.callable())))));
        }
        List<Destruction> destructions = new ArrayList<>();
        for (var type : contract.constructedRootTypes().stream().sorted(java.util.Comparator.comparing(IrType::displayName)).toList()) {
            var proof = BridgeDestructionAnalyzer.analyze(artifact, contract.roots(), type);
            if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
            var source = contract.roots().roots().stream().filter(root -> root.callable().owner().equals(type.referenceName()))
                    .findFirst().orElseThrow();
            var receiver = new IrValueReference(0, type, source.span());
            var function = new IrFunction(type.referenceName(), "<bridge-destroy>", "ironwood_bridge_destroy_" + destructions.size(),
                    IrType.VOID, List.of(new IrParameter("this", receiver, source.span())), List.of(new IrBasicBlock("entry",
                    List.of(new IrFreeInstruction(receiver, source.span())), new IrReturnTerminator(Optional.empty(), source.span()), source.span())),
                    source.span(), source.sourceFile(), IrCallableKind.METHOD);
            destructions.add(new Destruction(proof.contract().orElseThrow(), function));
        }
        List<IrFunction> functions = new ArrayList<>(original.functions());
        var generated = java.util.stream.Stream.concat(entries.stream().map(Entry::function), destructions.stream().map(Destruction::function)).toList();
        for (var function : generated) {
            if (functions.stream().anyMatch(existing -> existing.linkageName().equals(function.linkageName()))) {
                throw new IllegalArgumentException("generated bridge symbol collision: " + function.linkageName());
            }
            functions.add(function);
        }
        return new BridgeEntryModule(new IrProgram(original.moduleName(), original.classes(), original.staticFields(),
                original.typeInitializations(), original.arrayTypes(), original.stringConstants(), original.dispatchSlots(),
                functions, Optional.empty(), original.allocationFailure()), entries, Optional.of(contract), destructions,
                Optional.empty(), results);
    }

    /** Builds scalar-only entries; reference capabilities are rejected by this mode. */
    public static BridgeEntryModule scalars(CompilationArtifact artifact, BridgeRootSet requested) {
        return build(artifact, requested, false, false);
    }

    /** Scalar results and proved temporary String inputs; object results remain unsupported. */
    public static BridgeEntryModule copiedStrings(CompilationArtifact artifact, BridgeRootSet requested) {
        return build(artifact, requested, true, false);
    }

    /** Copied String values with proved result lifetime through JNI delivery. */
    public static BridgeEntryModule stringValues(CompilationArtifact artifact, BridgeRootSet requested) {
        return build(artifact, requested, true, true);
    }

    /** Named enum inputs and scalar results, with conversion and dispatch proofs. */
    public static BridgeEntryModule enums(CompilationArtifact artifact, BridgeRootSet requested,
                                         Map<IrType, Map<String, Integer>> tokens) {
        var proof = BridgeEnumInputs.prove(artifact, requested, tokens);
        var original = artifact.program().orElseThrow();
        var roots = requested.revalidate(original);
        List<Entry> entries = new ArrayList<>();
        for (var root : roots.roots()) {
            String symbol = "ironwood_bridge_entry_" + entries.size();
            if (original.functions().stream().anyMatch(function -> function.linkageName().equals(symbol))) {
                throw new IllegalArgumentException("generated bridge symbol collision: " + symbol);
            }
            var initialization = new BridgeCallTargets(original).initializers(root.callable().owner());
            if (!initialization.complete()) throw new IllegalArgumentException("incomplete entry initialization");
            entries.add(new Entry(root, BridgeEnumEntryLowering.lower(root, symbol, proof,
                    !initialization.targets().isEmpty(), artifact.bridgeConstructionFacts().orElseThrow().isStatic(root.callable()))));
        }
        List<IrFunction> functions = new ArrayList<>(original.functions());
        entries.forEach(entry -> functions.add(entry.function()));
        return new BridgeEntryModule(new IrProgram(original.moduleName(), original.classes(), original.staticFields(),
                original.typeInitializations(), original.arrayTypes(), original.stringConstants(), original.dispatchSlots(),
                functions, Optional.empty(), original.allocationFailure()), entries);
    }

    private static BridgeEntryModule build(CompilationArtifact artifact, BridgeRootSet requested,
            boolean strings, boolean stringResults) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()) {
            throw new IllegalArgumentException("bridge entry requires successful bridge semantic analysis");
        }
        var original = artifact.program().orElseThrow();
        if (!artifact.bridgeConstructionFacts().orElseThrow().matches(original)) {
            throw new IllegalArgumentException("bridge semantic facts do not match the input program");
        }
        var roots = requested.revalidate(original);
        if (!roots.resolved()) throw new IllegalArgumentException("bridge entry requires resolved roots");
        Map<BridgeCallableId, BridgeProof<BridgeRetentionContract>> retention = BridgeRetentionAnalyzer.analyze(original, roots,
                artifact.bridgeConstructionFacts().orElseThrow());
        Map<BridgeCallableId, BridgeStringResultContract> results = new java.util.LinkedHashMap<>();
        if (stringResults) {
            var selected = roots.roots().stream().map(BridgeRootSet.Root::callable)
                    .filter(callable -> callable.result().equals(IrType.reference("ironwood.lang.String"))).toList();
            if (!selected.isEmpty()) BridgeStringResults.prove(artifact, BridgeRootSet.resolve(original, selected))
                    .forEach((callable, proof) -> {
                        if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
                        results.put(callable, proof.contract().orElseThrow());
                    });
        }
        List<Entry> entries = new ArrayList<>();
        for (var root : roots.roots()) {
            var callable = root.callable();
            boolean hasStrings = callable.parameters().contains(IrType.reference("ironwood.lang.String"));
            if (callable.kind() != IrCallableKind.METHOD || callable.parameters().stream().anyMatch(type -> type.isReference()
                    && !(strings && type.equals(IrType.reference("ironwood.lang.String"))))
                    || callable.result().isReference() && !results.containsKey(callable)) {
                throw new IllegalArgumentException("scalar entry does not admit object, constructor or conversion capabilities");
            }
            if (strings && hasStrings) {
                if (!artifact.bridgeConstructionFacts().orElseThrow().isStatic(callable)) {
                    throw new IllegalArgumentException("copied String entry requires a resolved static method");
                }
                if (original.allocationFailure().isEmpty()) throw new IllegalArgumentException("copy requires allocation failure context");
                for (int index = 0; index < callable.parameters().size(); index++) {
                    if (callable.parameters().get(index).isReference()
                            && !(results.containsKey(callable)
                            ? artifact.bridgeConstructionFacts().orElseThrow().borrowsThroughResult(callable, index)
                            : artifact.bridgeConstructionFacts().orElseThrow().borrowsInput(callable, index))) {
                        throw new IllegalArgumentException("copied input cleanup is not proved for parameter " + index);
                    }
                }
            }
            var proof = retention.get(callable);
            if (proof.status() != BridgeProof.Status.PROVED || !proof.contract().orElseThrow().slots().isEmpty()) {
                throw new IllegalArgumentException("scalar entry retention is not proved: " + proof.reason());
            }
            String symbol = "ironwood_bridge_entry_" + entries.size();
            if (original.functions().stream().anyMatch(function -> function.linkageName().equals(symbol))) {
                throw new IllegalArgumentException("generated bridge symbol collision: " + symbol);
            }
            var initialization = new BridgeCallTargets(original).initializers(callable.owner());
            if (!initialization.complete()) throw new IllegalArgumentException("incomplete entry initialization");
            entries.add(new Entry(root, hasStrings || results.containsKey(callable)
                    ? BridgeStringEntryLowering.lower(root, symbol, !initialization.targets().isEmpty(),
                            Optional.ofNullable(results.get(callable)))
                    : lower(root, symbol, !initialization.targets().isEmpty())));
        }
        List<IrFunction> functions = new ArrayList<>(original.functions());
        entries.forEach(entry -> functions.add(entry.function()));
        var program = new IrProgram(original.moduleName(), original.classes(), original.staticFields(),
                original.typeInitializations(), original.arrayTypes(), original.stringConstants(), original.dispatchSlots(),
                functions, Optional.empty(), original.allocationFailure());
        return new BridgeEntryModule(program, entries, Optional.empty(), List.of(), Optional.empty(), results);
    }

    private static IrFunction lower(BridgeRootSet.Root root, String symbol, boolean initialize) {
        var span = root.span();
        var callable = root.callable();
        List<IrParameter> parameters = new ArrayList<>();
        for (var type : callable.parameters()) {
            int id = parameters.size();
            var value = new IrValueReference(id, type.equals(IrType.I1) ? IrType.I8 : type, span);
            parameters.add(new IrParameter("argument" + id, value, span));
        }
        var frame = new IrValueReference(parameters.size(), IrType.I64, span);
        parameters.add(new IrParameter("resultFrame", frame, span));
        int next = parameters.size();
        List<IrInstruction> preparation = new ArrayList<>();
        List<IrOperand> arguments = new ArrayList<>();
        for (int index = 0; index < callable.parameters().size(); index++) {
            var input = parameters.get(index).value();
            if (callable.parameters().get(index).equals(IrType.I1)) {
                var normalized = new IrValueReference(next++, IrType.I1, span);
                preparation.add(new IrBinaryInstruction(normalized, IrBinaryOperator.NOT_EQUAL, input,
                        new IrConstant(IrType.I8, 0, span), span));
                arguments.add(normalized);
            } else arguments.add(input);
        }
        Optional<IrValueReference> result = callable.result().equals(IrType.VOID) ? Optional.empty()
                : Optional.of(new IrValueReference(next++, callable.result(), span));
        var handle = new IrValueReference(next++, IrType.EXCEPTION, span);
        var exception = new IrValueReference(next++, IrType.EXCEPTION, span);
        var snapshotHandle = new IrValueReference(next++, IrType.EXCEPTION, span);
        var snapshotException = new IrValueReference(next, IrType.EXCEPTION, span);
        List<IrBasicBlock> blocks = List.of(
                new IrBasicBlock("entry", preparation, initialize ? new IrInvokeTerminator(
                        new IrEnsureTypeInitializedInstruction(callable.owner(), span), "target", "failure", span)
                        : new IrJump("target", span), span),
                new IrBasicBlock("target", List.of(), new IrInvokeTerminator(
                        new IrCallInstruction(result, callable.linkage(), callable.result(), arguments, span),
                        "success", "failure", span), span),
                new IrBasicBlock("success", result.<List<IrInstruction>>map(value -> List.of(
                        new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.VALUE, value, span)))
                        .orElse(List.of()), new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 0, span)), span), span),
                new IrBasicBlock("failure", List.of(new IrExceptionLandingPadInstruction(handle, exception, span),
                        new IrExceptionCaughtInstruction(exception, span),
                        new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.EXCEPTION, exception, span)),
                        new IrInvokeTerminator(new IrBridgeFailureSnapshotInstruction(exception, frame, span),
                                "snapshot.complete", "snapshot.failure", span), span),
                new IrBasicBlock("snapshot.complete", List.of(),
                        new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 1, span)), span), span),
                new IrBasicBlock("snapshot.failure", List.of(
                        new IrExceptionLandingPadInstruction(snapshotHandle, snapshotException, span),
                        new IrExceptionCaughtInstruction(snapshotException, span)),
                        new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 2, span)), span), span));
        return new IrFunction(callable.owner(), "<bridge-entry>", symbol, IrType.I32, parameters, blocks,
                span, root.sourceFile(), IrCallableKind.METHOD);
    }
}
