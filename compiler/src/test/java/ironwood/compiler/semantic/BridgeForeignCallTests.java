// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.UnfreedMode;
import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Foreign effects must survive helper/dispatch edges and protected control flow. */
public final class BridgeForeignCallTests {
    public static final String NAME = "Java Bridge typed foreign calls preserve conservative effects and unwind edges";
    private static final SourceSpan SPAN = SourceSpan.at(new SourcePosition(0, 1, 1));
    private static final IrType ITEM = IrType.reference("ForeignEffects");
    private static final IrValueReference INPUT = new IrValueReference(0, ITEM, SPAN);
    private static final IrValueReference OUTPUT = new IrValueReference(1, ITEM, SPAN);
    private static final List<IrParameter> PARAMETERS = List.of(new IrParameter("input", INPUT, SPAN));
    private static final IrReturnTerminator DONE = new IrReturnTerminator(Optional.empty(), SPAN);

    private BridgeForeignCallTests() {}

    public static void proofs() {
        var foreign = new IrForeignCallInstruction(Optional.of(OUTPUT), "ironwood_bridge_callback_result",
                ITEM, List.of(INPUT), SPAN);
        var direct = function("direct", List.of(new IrBasicBlock("entry", List.of(foreign),
                new IrReturnTerminator(Optional.of(OUTPUT), SPAN), SPAN)), ITEM);
        var safe = function("safe", List.of(new IrBasicBlock("entry", List.of(), DONE, SPAN)), IrType.VOID);
        var slot = new IrDispatchSlot(0, "run()", "run", ITEM, List.of(), SPAN);
        var type = new IrClass("ForeignEffects", IrTypeKind.CLASS, Optional.empty(), List.of(), List.of(),
                0, List.of(0), List.of(new IrDispatchEntry(slot, "direct")), SPAN);
        var functions = new ArrayList<>(List.of(direct, safe));
        functions.add(function("helper", List.of(new IrBasicBlock("entry", List.of(new IrCallInstruction(
                Optional.of(OUTPUT), "direct", ITEM, List.of(INPUT), SPAN)),
                new IrReturnTerminator(Optional.of(OUTPUT), SPAN), SPAN)), ITEM));
        functions.add(function("dispatch", List.of(new IrBasicBlock("entry", List.of(new IrInterfaceCallInstruction(
                Optional.of(OUTPUT), "Listener", slot, ITEM, List.of(INPUT), SPAN)),
                new IrReturnTerminator(Optional.of(OUTPUT), SPAN), SPAN)), ITEM));
        functions.add(function("caught", List.of(
                new IrBasicBlock("entry", List.of(), new IrInvokeTerminator(foreign, "done", "failure", SPAN), SPAN),
                new IrBasicBlock("done", List.of(), DONE, SPAN),
                new IrBasicBlock("failure", List.of(), DONE, SPAN)), IrType.VOID));
        // A coincidentally matching native symbol cannot close the Java call edge.
        functions.add(function("ironwood_bridge_callback_result", safe.blocks(), IrType.VOID));
        var program = new IrProgram("foreign", List.of(type), List.of(),
                List.of(new IrTypeInitialization(type.name(), List.of(), Optional.empty(), SPAN)),
                List.of(), List.of(), List.of(slot), functions, Optional.empty(), Optional.empty());
        var effects = new ClosedWorldEffectAnalyzer(functions, List.of(type));
        effects.analyze();
        check(effects.nonThrowingAndAllocationFree("safe"), "safe control lost cleanup proof");
        for (String name : List.of("direct", "helper", "dispatch", "caught")) {
            check(!effects.nonThrowingAndAllocationFree(name), "foreign allocation hidden by " + name);
            String summary = effects.observerProjection().get(name);
            check(summary.contains("publishedParameters={0}"), "foreign publication hidden: " + summary);
            check(summary.contains("reclaimedParameters={0}"), "foreign reentry hidden: " + summary);
            if (!name.equals("caught")) {
                check(summary.contains("returnedParameters={0}"), "foreign result lost possible alias: " + summary);
                check(summary.contains("throwsOutward=true"), "foreign throw hidden: " + summary);
            }
            var root = BridgeRootSet.resolve(program, List.of(BridgeCallableId.of(functions.stream()
                    .filter(function -> function.linkageName().equals(name)).findFirst().orElseThrow())));
            check(BridgeNonReclamationAnalyzer.analyze(program, root, ITEM).status() == BridgeProof.Status.UNKNOWN,
                    "foreign reentry granted non-reclamation through " + name);
            check(BridgeRetentionAnalyzer.analyze(program, root).values().stream()
                    .noneMatch(proof -> proof.status() == BridgeProof.Status.PROVED),
                    "foreign publication granted borrowing through " + name);
        }
        check(effects.observerProjection().get("caught").contains("throwsOutward=false"),
                "handled foreign throw incorrectly escapes");
        check(new BridgeControlFlow(program).hasUnwindEdge(functions.stream()
                .filter(function -> function.linkageName().equals("caught")).findFirst().orElseThrow(),
                "entry", "failure"), "foreign exceptional edge removed");
        check(effects.mayUnwind(foreign), "foreign instruction became nonthrowing");
        check(effects.possiblyReclaimedArguments(foreign).get(0), "foreign reference reclamation omitted");
        check(!TemporaryBorrowAnalysis.Check.supported(foreign), "foreign operation granted temporary borrow exemption");
        var edge = new BridgeCallTargets(program).resolve(foreign);
        check(!edge.complete() && edge.targets().isEmpty() && edge.arguments().equals(List.of(INPUT)),
                "foreign target resolved as ordinary closed-world code");
        var renamed = (IrForeignCallInstruction) new IrCfgRenamer(value -> new IrValueReference(
                value.id() + 10, value.type(), value.sourceSpan()), label -> "copy_" + label).instruction(foreign);
        check(renamed.result().orElseThrow().id() == 11
                && ((IrValueReference) renamed.arguments().getFirst()).id() == 10
                && renamed.sourceSpan().equals(SPAN), "foreign SSA operands or source attribution lost");
        expectRefusal(() -> new LlvmEmitter().emit(program), "invocation-context lowering");
        expectRefusal(() -> new IrForeignCallInstruction(Optional.of(INPUT), "ironwood_bridge_callback_bad",
                IrType.I64, List.of(), SPAN), "declared type");
        expectRefusal(() -> new IrForeignCallInstruction(Optional.empty(), "user_function",
                IrType.VOID, List.of(), SPAN), "compiler-owned");
        sourceSummaries(direct);
        producerBoundary();
    }

    private static void sourceSummaries(IrFunction direct) {
        var source = SourceFile.of("ForeignEffects.iron", """
                class ForeignEffects {
                    static ForeignEffects direct(ForeignEffects input) { return input; }
                    static ForeignEffects helper(ForeignEffects input) { return direct(input); }
                    static ForeignEffects safe(ForeignEffects input) { return input; }
                }
                """);
        var lexed = new ironwood.compiler.lexer.Lexer(source).lex();
        var parsed = new ironwood.compiler.parser.Parser(source, lexed.tokens()).parse();
        check(lexed.diagnostics().isEmpty() && parsed.diagnostics().isEmpty(), "source summary fixture did not parse");
        var declaration = ironwood.compiler.ast.DeclaredTypes.in(parsed.unit().orElseThrow()).getFirst();
        var type = new TypeSymbol(declaration, false);
        for (var method : ((ironwood.compiler.ast.ClassDeclaration) type.declaration()).methods()) {
            type.addMethod(new CallableSymbol(type.name(), method.name(), method.accessModifier(), true,
                    IrCallableKind.METHOD, ITEM, List.of(ITEM), method.parameters(), method.body(),
                    Optional.empty(), Optional.empty(), false, false, false, method.nameSpan(), method.span(), method.name()));
        }
        var types = java.util.Map.of(type.name(), type);
        var resolver = new TypeResolver(types);
        var generics = new GenericTypeSystem(types, resolver);
        var diagnostics = new ArrayList<ironwood.compiler.diagnostic.Diagnostic>();
        generics.initializeDeclaredBounds(diagnostics);
        var hierarchy = new ClassHierarchy(types, resolver, generics);
        generics.attachHierarchy(hierarchy);
        generics.markHierarchyReady(diagnostics);
        check(diagnostics.isEmpty(), diagnostics.toString());
        var dispatch = new BorrowDispatchAnalysis(types, hierarchy, List.of(direct), List.of(), false);
        var summaries = new EscapeSummaryAnalyzer(types, resolver, null, dispatch, java.util.Map.of());
        for (String name : List.of("direct", "helper")) {
            var summary = summaries.summary(type.declaredMethodsNamed(name).getFirst());
            check(summary.parameterEscapesWithoutReturn(0), "source stub hid foreign publication in " + name);
            check(!summary.returnsOwnedFresh(), "unknown foreign result became owned fresh in " + name);
            check(summary.mayReturnNonOrigin(), "source stub supplied a false exact return in " + name);
        }
        check(!summaries.summary(type.declaredMethodsNamed("safe").getFirst()).parameterEscapesWithoutReturn(0),
                "unrelated source borrowing changed");
    }

    private static void producerBoundary() {
        for (var mode : UnfreedMode.values()) {
            var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Listener.iron", """
                    package listeners;
                    public interface Listener { void onResult(long sequence, long value); }
                    """)));
            check(artifact.valid(), artifact.diagnostics().toString());
            check(ironwood.compiler.bridge.BridgeExportSurface.objectValues(artifact, List.of("listeners"))
                    .surface().isEmpty(), "incomplete callback export admitted in " + mode);
        }
    }

    private static IrFunction function(String name, List<IrBasicBlock> blocks, IrType result) {
        return new IrFunction("ForeignEffects", name, name, result, PARAMETERS, blocks, SPAN);
    }

    private static void expectRefusal(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException failure) {
            check(failure.getMessage().contains(message), failure.toString());
            return;
        }
        throw new AssertionError("expected refusal: " + message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
