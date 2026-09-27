// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Protected, nonrecursive follow-up entries used only by the native failure translator. */
public final class BridgeExceptionEntries {
    public static final int SUCCESS = 0;
    public static final int ALLOCATION_FAILURE = 2;
    public static final int EXTRACTION_FAILURE = 3;

    private final IrProgram program;
    private final Map<BridgeExceptionProjection.Property, IrFunction> accessors;
    private final IrFunction trace;

    private BridgeExceptionEntries(IrProgram program,
            Map<BridgeExceptionProjection.Property, IrFunction> accessors, IrFunction trace) {
        this.program = program;
        this.accessors = Map.copyOf(accessors);
        this.trace = trace;
    }

    public IrProgram program() { return program; }
    public Map<BridgeExceptionProjection.Property, IrFunction> accessors() { return accessors; }
    public IrFunction trace() { return trace; }

    public static BridgeExceptionEntries attach(CompilationArtifact analyzed, BridgeEntryModule entries,
            BridgeExceptionProjection projection) {
        if (!analyzed.valid() || analyzed.program().isEmpty() || !projection.matches(analyzed.program().orElseThrow())) {
            throw new IllegalArgumentException("exception entries require the original projected program");
        }
        var original = analyzed.program().orElseThrow();
        var base = entries.program();
        var restored = new IrProgram(base.moduleName(), base.classes(), base.staticFields(), base.typeInitializations(),
                base.arrayTypes(), base.stringConstants(), base.dispatchSlots(), original.functions(), original.entryPoint(),
                base.allocationFailure(), original.exportRoots());
        if (!restored.equals(original) || !base.functions().containsAll(original.functions())) {
            throw new IllegalArgumentException("exception entries cannot attach to a different native program");
        }
        if (original.allocationFailure().isEmpty()) throw new IllegalArgumentException("exception getters need allocation failure context");
        var oom = original.classes().stream().filter(type -> type.name().equals("ironwood.lang.OutOfMemoryError"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("missing allocation failure type"));
        Map<BridgeExceptionProjection.Property, IrFunction> generated = new LinkedHashMap<>();
        for (var type : projection.types()) for (var property : type.properties()) {
            if (generated.containsKey(property)) continue;
            IrFunction owner = property.callable().map(id -> original.functions().stream()
                    .filter(function -> BridgeCallableId.of(function).equals(id)).findFirst().orElseThrow())
                    .orElseGet(() -> original.functions().stream().filter(function -> function.ownerClass()
                            .equals(property.field().orElseThrow().ownerClass())).findFirst().orElseThrow());
            generated.put(property, getter(property, owner, "ironwood_bridge_exception_get_" + generated.size(), oom.typeId()));
        }
        var first = projection.accessors().roots().getFirst();
        var trace = trace(first.span(), first.sourceFile(), oom.typeId());
        List<IrFunction> functions = new ArrayList<>(base.functions());
        var exports = new LinkedHashSet<>(base.exportRoots());
        List<IrFunction> additions = new ArrayList<>(generated.values());
        additions.add(trace);
        for (var function : additions) {
            if (functions.stream().anyMatch(existing -> existing.linkageName().equals(function.linkageName()))) {
                throw new IllegalArgumentException("exception entry symbol collision: " + function.linkageName());
            }
            functions.add(function);
            exports.add(function.linkageName());
        }
        return new BridgeExceptionEntries(new IrProgram(base.moduleName(), base.classes(), base.staticFields(),
                base.typeInitializations(), base.arrayTypes(), base.stringConstants(), base.dispatchSlots(), functions,
                Optional.empty(), base.allocationFailure(), exports), generated, trace);
    }

    private static IrFunction getter(BridgeExceptionProjection.Property property, IrFunction owner,
            String symbol, int oomTypeId) {
        var span = property.field().map(IrField::sourceSpan).orElse(owner.sourceSpan());
        List<IrParameter> parameters = new ArrayList<>();
        List<IrOperand> arguments = new ArrayList<>();
        var types = property.callable().map(BridgeCallableId::parameters)
                .orElseGet(() -> List.of(IrType.reference(property.field().orElseThrow().ownerClass())));
        for (var type : types) {
            var value = new IrValueReference(parameters.size(), type, span);
            parameters.add(new IrParameter("argument" + parameters.size(), value, span));
            arguments.add(value);
        }
        var frame = new IrValueReference(parameters.size(), IrType.I64, span);
        parameters.add(new IrParameter("resultFrame", frame, span));
        var result = new IrValueReference(parameters.size(), property.type(), span);
        var stored = new IrBridgeResultStoreInstruction(frame, IrBridgeResultStoreInstruction.Slot.VALUE, result, span);
        List<IrBasicBlock> blocks = new ArrayList<>();
        if (property.callable().isPresent()) {
            blocks.add(new IrBasicBlock("entry", List.of(), new IrInvokeTerminator(new IrCallInstruction(
                    Optional.of(result), property.callable().orElseThrow().linkage(), property.type(), arguments, span),
                    "success", "failure", span), span));
            blocks.add(new IrBasicBlock("success", List.of(stored), returned(SUCCESS, span), span));
            failure(blocks, result.id() + 1, oomTypeId, span);
        } else blocks.add(new IrBasicBlock("entry", List.of(new IrFieldLoadInstruction(result, arguments.getFirst(),
                property.field().orElseThrow(), span), stored), returned(SUCCESS, span), span));
        return new IrFunction(owner.ownerClass(), "<bridge-exception-get>", symbol, IrType.I32, parameters, blocks,
                span, owner.sourceFileName(), IrCallableKind.METHOD);
    }

    private static IrFunction trace(SourceSpan span, String file, int oomTypeId) {
        var throwable = new IrValueReference(0, IrType.reference("ironwood.lang.Throwable"), span);
        var frame = new IrValueReference(1, IrType.I64, span);
        List<IrBasicBlock> blocks = new ArrayList<>();
        blocks.add(new IrBasicBlock("entry", List.of(), new IrInvokeTerminator(
                new IrBridgeFailureSnapshotInstruction(throwable, frame, span), "success", "failure", span), span));
        blocks.add(new IrBasicBlock("success", List.of(), returned(SUCCESS, span), span));
        failure(blocks, 2, oomTypeId, span);
        return new IrFunction("ironwood.lang.Throwable", "<bridge-exception-trace>", "ironwood_bridge_exception_trace",
                IrType.I32, List.of(new IrParameter("throwable", throwable, span), new IrParameter("resultFrame", frame, span)),
                blocks, span, file, IrCallableKind.METHOD);
    }

    private static void failure(List<IrBasicBlock> blocks, int firstValue, int oomTypeId, SourceSpan span) {
        var handle = new IrValueReference(firstValue, IrType.EXCEPTION, span);
        var exception = new IrValueReference(firstValue + 1, IrType.EXCEPTION, span);
        var allocation = new IrValueReference(firstValue + 2, IrType.I1, span);
        blocks.add(new IrBasicBlock("failure", List.of(new IrExceptionLandingPadInstruction(handle, exception, span),
                new IrExceptionCaughtInstruction(exception, span), new IrInstanceOfInstruction(allocation, exception,
                "ironwood.lang.OutOfMemoryError", oomTypeId, span)), new IrBranch(allocation, "allocation.failure", "extraction.failure", span), span));
        blocks.add(new IrBasicBlock("allocation.failure", List.of(), returned(ALLOCATION_FAILURE, span), span));
        blocks.add(new IrBasicBlock("extraction.failure", List.of(), returned(EXTRACTION_FAILURE, span), span));
    }

    private static IrReturnTerminator returned(int status, SourceSpan span) {
        return new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, status, span)), span);
    }
}
