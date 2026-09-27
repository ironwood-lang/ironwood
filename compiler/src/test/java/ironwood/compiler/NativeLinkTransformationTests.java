// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class NativeLinkTransformationTests {
    static final String NAME = "native-link provenance preserves conservative facts through actual specialized clones";

    private NativeLinkTransformationTests() {}

    static void proofs() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/enum_argument_specialization.iron"))
                .replace("class Main {", "class Item { int value; Item() { value = Axis.UP.value; } } class Main {")
                .replace("static int score(Axis axis)", "static Axis identity(Axis axis) { return axis; } static int score(Axis axis)")
                .replace("score(Axis.UP)", "score(identity(Axis.UP))")
                .replace("sum += recursive(Axis.UP, 1);", "sum += recursive(Axis.UP, 1); Item item = new Item(); sum += item.value; free item;");
        for (var mode : UnfreedMode.values()) {
            var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("case.iron", source)));
            check(artifact.valid(), artifact.diagnostics().toString());
            var original = artifact.program().orElseThrow();
            var facts = artifact.bridgeConstructionFacts().orElseThrow();
            var transformed = NativeLinkTransformation.apply(original);
            var result = transformed.program();
            var expected = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(original));
            check(result.equals(expected), "recorded pipeline changed typed output");
            check(new LlvmEmitter().emit(result).equals(new LlvmEmitter().emit(expected)), "recording changed LLVM output");
            var finalFacts = facts.afterNativeLink(transformed);
            check(finalFacts.matches(result) && !facts.matches(result), "facts did not require recorded transformation");
            check(result.functions().stream().anyMatch(function -> function.linkageName().contains(".$initialized.")),
                    "fixture did not produce guarded initialization clones");
            check(result.functions().stream().anyMatch(function -> function.linkageName().contains(".$enumarg.")),
                    "fixture did not produce enum identity clones");
            boolean referenceClone = false;
            boolean constructorClone = false;
            for (var function : result.functions()) {
                var target = BridgeCallableId.of(function);
                var origin = transformed.origins().get(target);
                check(origin != null && original.functions().stream().anyMatch(candidate -> BridgeCallableId.of(candidate).equals(origin)),
                        "clone lacks an actual input origin");
                for (int index = 0; index < target.parameters().size(); index++) {
                    check(finalFacts.borrowsInput(target, index) == facts.borrowsInput(origin, index)
                            && finalFacts.borrowsThroughResult(target, index) == facts.borrowsThroughResult(origin, index),
                            "specialization upgraded or lost source borrowing facts");
                }
                if (!target.equals(origin) && target.result().isReference()) {
                    referenceClone = true;
                    var proof = finalFacts.resultOrigins().get(target);
                    check(proof == null || proof.status() == BridgeProof.Status.UNKNOWN, "enum substitution inherited parameter result origins");
                }
                if (!target.equals(origin) && function.constructor()) {
                    constructorClone = true;
                    var proof = finalFacts.constructors().get(target);
                    check(proof != null && proof.status() == facts.constructors().get(origin).status(), "constructor clone lost source status");
                    if (proof.status() == BridgeProof.Status.PROVED) {
                        check(proof.contract().orElseThrow().constructor().equals(target), "constructor clone kept stale identity");
                    }
                }
            }
            check(referenceClone && constructorClone, "fixture lost reference-result or constructor clone");
            var foreign = new IrProgram(original.moduleName() + ".foreign", original.classes(), original.staticFields(), original.typeInitializations(),
                    original.arrayTypes(), original.stringConstants(), original.dispatchSlots(), original.functions(), original.entryPoint(),
                    original.allocationFailure(), original.exportRoots());
            denied(() -> facts.afterNativeLink(NativeLinkTransformation.apply(foreign)));
            check(!finalFacts.matches(foreign), "final facts accepted a foreign program");
        }
        var unknown = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("unknown.iron",
                source.replace("return axis;", "long tick = System.nanoTime(); return axis;"))));
        check(unknown.valid(), unknown.diagnostics().toString());
        var transformed = NativeLinkTransformation.apply(unknown.program().orElseThrow());
        var facts = unknown.bridgeConstructionFacts().orElseThrow().afterNativeLink(transformed);
        var program = transformed.program();
        var roots = BridgeRootSet.resolve(program, program.functions().stream().filter(function -> function.sourceName().equals("drive"))
                .map(BridgeCallableId::of).toList());
        var proof = BridgeNonReclamationAnalyzer.analyze(program, roots, IrType.reference("Axis"), facts);
        check(proof.status() == BridgeProof.Status.UNKNOWN && proof.reason().contains("IrSystemClockInstruction"),
                "recorded optimization erased unknown reclamation effects: " + proof.reason());
    }

    private static void denied(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("foreign transformation admitted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
