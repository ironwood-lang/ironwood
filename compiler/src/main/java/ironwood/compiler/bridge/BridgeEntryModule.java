// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.semantic.BridgeCallTargets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Internal protected-entry lowering shared by the P0 harness and later producer. */
public final class BridgeEntryModule {
    public record Entry(BridgeRootSet.Root root, IrFunction function) {}

    private final IrProgram program;
    private final List<Entry> entries;

    private BridgeEntryModule(IrProgram program, List<Entry> entries) {
        this.program = program;
        this.entries = List.copyOf(entries);
    }

    public IrProgram program() { return program; }
    public List<Entry> entries() { return entries; }
    public Set<String> entrySymbols() {
        return entries.stream().map(entry -> entry.function().linkageName()).collect(Collectors.toUnmodifiableSet());
    }

    /** Builds scalar-only entries; reference capabilities are rejected by this mode. */
    public static BridgeEntryModule scalars(CompilationArtifact artifact, BridgeRootSet requested) {
        return build(artifact, requested, false);
    }

    /** Scalar results and proved temporary String inputs; object results remain unsupported. */
    public static BridgeEntryModule copiedStrings(CompilationArtifact artifact, BridgeRootSet requested) {
        return build(artifact, requested, true);
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

    private static BridgeEntryModule build(CompilationArtifact artifact, BridgeRootSet requested, boolean strings) {
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()) {
            throw new IllegalArgumentException("bridge entry requires successful bridge semantic analysis");
        }
        var original = artifact.program().orElseThrow();
        if (!artifact.bridgeConstructionFacts().orElseThrow().matches(original)) {
            throw new IllegalArgumentException("bridge semantic facts do not match the input program");
        }
        var roots = requested.revalidate(original);
        if (!roots.resolved()) throw new IllegalArgumentException("bridge entry requires resolved roots");
        Map<BridgeCallableId, BridgeProof<BridgeRetentionContract>> retention = BridgeRetentionAnalyzer.analyze(original, roots);
        List<Entry> entries = new ArrayList<>();
        for (var root : roots.roots()) {
            var callable = root.callable();
            boolean hasStrings = callable.parameters().contains(IrType.reference("ironwood.lang.String"));
            if (callable.kind() != IrCallableKind.METHOD || callable.parameters().stream().anyMatch(type -> type.isReference()
                    && !(strings && type.equals(IrType.reference("ironwood.lang.String"))))
                    || callable.result().isReference()) {
                throw new IllegalArgumentException("scalar entry does not admit object, constructor or conversion capabilities");
            }
            if (strings && hasStrings) {
                if (!artifact.bridgeConstructionFacts().orElseThrow().isStatic(callable)) {
                    throw new IllegalArgumentException("copied String entry requires a resolved static method");
                }
                if (original.allocationFailure().isEmpty()) throw new IllegalArgumentException("copy requires allocation failure context");
                for (int index = 0; index < callable.parameters().size(); index++) {
                    if (callable.parameters().get(index).isReference()
                            && !artifact.bridgeConstructionFacts().orElseThrow().borrowsInput(callable, index)) {
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
            entries.add(new Entry(root, hasStrings
                    ? BridgeStringEntryLowering.lower(root, symbol, !initialization.targets().isEmpty())
                    : lower(root, symbol, !initialization.targets().isEmpty())));
        }
        List<IrFunction> functions = new ArrayList<>(original.functions());
        entries.forEach(entry -> functions.add(entry.function()));
        var program = new IrProgram(original.moduleName(), original.classes(), original.staticFields(),
                original.typeInitializations(), original.arrayTypes(), original.stringConstants(), original.dispatchSlots(),
                functions, Optional.empty(), original.allocationFailure());
        return new BridgeEntryModule(program, entries);
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
