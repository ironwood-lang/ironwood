// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.UnfreedMode;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BridgeControlFlowTests {
    private BridgeControlFlowTests() {}

    public static void proofs() {
        var span = SourceSpan.at(new SourcePosition(0, 1, 1));
        var string = IrType.reference("ironwood.lang.String");
        var object = new IrValueReference(0, IrType.reference("ironwood.lang.Throwable"), span);
        var text = new IrValueReference(1, string, span);
        var result = new IrValueReference(2, string, span);
        var parameters = List.of(new IrParameter("object", object, span), new IrParameter("text", text, span));
        var arguments = List.<IrOperand>of(object, text);
        var done = new IrReturnTerminator(Optional.empty(), span);
        var zero = new IrConstant(IrType.I32, 0, span);
        var functions = new ArrayList<IrFunction>();
        functions.add(new IrFunction("Flow", "cleanup", "cleanup", IrType.VOID, parameters,
                List.of(new IrBasicBlock("entry", List.of(new IrReleaseOwnedToStringResultInstruction(object, text, span)), done, span)), span));
        functions.add(new IrFunction("Flow", "helper", "helper", IrType.VOID, parameters,
                List.of(new IrBasicBlock("entry", List.of(new IrCallInstruction(Optional.empty(), "cleanup", IrType.VOID, arguments, span)), done, span)), span));
        functions.add(new IrFunction("Flow", "allocating", "allocating", IrType.VOID, parameters,
                List.of(new IrBasicBlock("entry", List.of(new IrStringCopyInstruction(result, text, span)), done, span)), span));
        functions.add(new IrFunction("Flow", "throwing", "throwing", IrType.VOID, parameters,
                List.of(new IrBasicBlock("entry", List.of(), new IrThrowTerminator(object, "unreachable", Optional.empty(), span), span)), span));
        functions.add(new IrFunction("Flow", "unknown", "unknown", IrType.VOID, parameters,
                List.of(new IrBasicBlock("entry", List.of(new IrSystemArrayCopyInstruction(object, zero, object, zero, zero, span)), done, span)), span));
        functions.add(new IrFunction("Flow", "unknownHelper", "unknownHelper", IrType.VOID, parameters,
                List.of(new IrBasicBlock("entry", List.of(new IrCallInstruction(Optional.empty(), "unknown", IrType.VOID, arguments, span)), done, span)), span));
        var literal = new IrStringConstant(0, "literal", 7, 7, span);
        for (String callee : List.of("cleanup", "helper", "allocating", "throwing", "unknown", "unknownHelper", "missing")) {
            String name = "caller" + callee;
            var exception = new IrValueReference(3, IrType.EXCEPTION, span);
            var occurrence = new IrValueReference(4, IrType.EXCEPTION, span);
            functions.add(new IrFunction("Flow", name, name, string, parameters, List.of(
                    new IrBasicBlock("entry", List.of(), new IrInvokeTerminator(new IrCallInstruction(Optional.empty(),
                            callee, IrType.VOID, arguments, span), "normal", "failure", span), span),
                    new IrBasicBlock("normal", List.of(), new IrJump("join", span), span),
                    new IrBasicBlock("failure", List.of(new IrExceptionLandingPadInstruction(exception, occurrence, span),
                            new IrExceptionCaughtInstruction(occurrence, span), new IrAddSecondaryExceptionInstruction(object, exception, span)),
                            new IrJump("join", span), span),
                    new IrBasicBlock("join", List.of(new IrPhiInstruction(result, List.of(new IrPhiIncoming("normal", literal),
                            new IrPhiIncoming("failure", text)), span)), new IrReturnTerminator(Optional.of(result), span), span)), span));
        }
        var flag = new IrValueReference(5, IrType.I1, span);
        var mixedParameters = new ArrayList<>(parameters);
        mixedParameters.add(new IrParameter("flag", flag, span));
        var mixed = new IrFunction("Flow", "mixed", "mixed", string, mixedParameters, List.of(
                new IrBasicBlock("entry", List.of(), new IrBranch(flag, "safe", "unsafe", span), span),
                new IrBasicBlock("safe", List.of(), new IrInvokeTerminator(new IrCallInstruction(Optional.empty(), "cleanup",
                        IrType.VOID, arguments, span), "done", "failure", span), span),
                new IrBasicBlock("unsafe", List.of(), new IrInvokeTerminator(new IrCallInstruction(Optional.empty(), "allocating",
                        IrType.VOID, arguments, span), "done", "failure", span), span),
                new IrBasicBlock("done", List.of(), new IrReturnTerminator(Optional.of(literal), span), span),
                new IrBasicBlock("failure", List.of(new IrPhiInstruction(result, List.of(new IrPhiIncoming("safe", text),
                        new IrPhiIncoming("unsafe", literal)), span),
                        new IrExceptionLandingPadInstruction(new IrValueReference(6, IrType.EXCEPTION, span),
                                new IrValueReference(7, IrType.EXCEPTION, span), span),
                        new IrExceptionCaughtInstruction(new IrValueReference(7, IrType.EXCEPTION, span), span)),
                        new IrReturnTerminator(Optional.of(result), span), span)), span);
        functions.add(mixed);
        var program = new IrProgram("flow", List.of(), List.of(),
                List.of(new IrTypeInitialization("Flow", List.of(), Optional.empty(), span)), List.of(), List.of(literal), List.of(),
                functions, Optional.empty(), Optional.empty());
        var roots = BridgeRootSet.resolve(program, functions.stream().filter(function -> function.sourceName().startsWith("caller"))
                .map(BridgeCallableId::of).toList());
        var proofs = BridgeRetentionAnalyzer.analyze(program, roots);
        var immortal = BridgeRetentionAnalyzer.immortalStringResults(program, roots);
        var flow = new BridgeControlFlow(program);
        var failurePhi = (IrPhiInstruction) flow.blocks(mixed).stream().filter(block -> block.label().equals("failure"))
                .findFirst().orElseThrow().instructions().getFirst();
        check(failurePhi.incoming().size() == 1 && failurePhi.incoming().getFirst().predecessor().equals("unsafe"),
                "a reachable predecessor with a removed unwind edge remained in the phi");
        check(BridgeRetentionAnalyzer.immortalStringResults(program, BridgeRootSet.resolve(program, List.of(BridgeCallableId.of(mixed))))
                .contains(BridgeCallableId.of(mixed)), "removed unwind edge contaminated String result origins");
        for (var entry : proofs.entrySet()) {
            boolean safe = entry.getKey().name().equals("callercleanup") || entry.getKey().name().equals("callerhelper");
            check((entry.getValue().status() == BridgeProof.Status.PROVED) == safe, entry.toString());
            check(immortal.contains(entry.getKey()) == safe, "dead/live phi input changed result origin: " + entry);
            var function = functions.stream().filter(value -> value.linkageName().equals(entry.getKey().linkage())).findFirst().orElseThrow();
            check(flow.blocks(function).stream().anyMatch(block -> block.label().equals("failure")) != safe,
                    "unwind edge proof incomplete: " + entry.getKey());
        }
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Failures.iron", """
                class Failures {
                    static int parsed() { throw new ironwood.time.format.DateTimeParseException("detail", "text", 2); }
                }
                """)));
        check(artifact.valid(), artifact.diagnostics().toString());
        verifyNativeCleanup(artifact);
    }

    private static void verifyNativeCleanup(CompilationArtifact artifact) {
        var program = artifact.program().orElseThrow();
        var roots = BridgeRootSet.resolve(program, program.functions().stream().filter(function -> function.ownerClass().equals("Failures")
                && function.sourceName().equals("parsed")).map(BridgeCallableId::of).toList());
        var proof = BridgeRetentionAnalyzer.analyze(program, roots, artifact.bridgeConstructionFacts().orElseThrow()).values().iterator().next();
        check(proof.status() == BridgeProof.Status.PROVED && proof.contract().orElseThrow().slots().isEmpty(),
                "native cleanup lost precise unwind reachability: " + proof);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
