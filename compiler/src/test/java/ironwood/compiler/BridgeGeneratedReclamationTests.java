// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class BridgeGeneratedReclamationTests {
    static final String NAME = "Java Bridge generated non-reclamation checks include destruction and fixed transport effects";
    private static final String SOURCE = """
            package generatedproof;
            public final class Holder {
                private Item item;
                public Holder(Item item) { this.item = item; }
                public void store(Item value) { item = value; }
                public void clear() { item = null; }
                public int text(String input, Mode value) { return input.length() + value.code(); }
                public static final class Item { public Item() {} }
                public enum Mode { FIRST; public int code() { return 17; } }
            }
            """;

    private BridgeGeneratedReclamationTests() {}

    static void proofs() {
        for (var mode : UnfreedMode.values()) {
            var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Holder.iron", SOURCE)));
            check(artifact.valid(), artifact.diagnostics().toString());
            var selected = BridgeExportSurface.objectValues(artifact, List.of("generatedproof"));
            check(selected.surface().isPresent(), selected.diagnostics().toString());
            var surface = selected.surface().orElseThrow();
            var module = BridgeEntryModule.rootObjects(artifact, surface.roots(), BridgeEnumConversions.forSurface(artifact, surface));
            Set<Class<?>> generatedOperations = new java.util.HashSet<>();
            for (var function : module.program().functions()) for (var block : function.blocks()) {
                block.instructions().forEach(instruction -> generatedOperations.add(instruction.getClass()));
                if (block.terminator() instanceof IrInvokeTerminator invoke) generatedOperations.add(invoke.call().getClass());
            }
            check(generatedOperations.containsAll(Set.of(IrBridgeStringCopyInstruction.class, IrBridgeFailureSnapshotInstruction.class,
                    IrBridgeResultStoreInstruction.class, IrBridgeSlotStoreInstruction.class)), "fixture lost a generated transport operation");
            var enumType = IrType.reference("generatedproof.Holder$Mode");
            var generatedFacts = artifact.bridgeConstructionFacts().orElseThrow().withGeneratedEntries(module);
            var transformation = NativeLinkTransformation.apply(module.program());
            for (var program : List.of(module.program(), transformation.program())) {
                var facts = program.equals(module.program()) ? generatedFacts : generatedFacts.afterNativeLink(transformation);
                for (var type : List.of(IrType.reference("generatedproof.Holder"), IrType.reference("generatedproof.Holder$Item"))) {
                    check(BridgeNonReclamationAnalyzer.analyze(program, roots(program), type, facts).status()
                            == BridgeProof.Status.REJECTED, "generated construction facts hid exposed destruction");
                }
            }
            for (var program : List.of(module.program(), NativeLinkPipeline.finish(NativeLinkPipeline.optimize(module.program())))) {
                var roots = roots(program);
                var permanent = BridgeNonReclamationAnalyzer.analyze(program, roots, enumType);
                check(permanent.status() == BridgeProof.Status.PROVED, permanent.reason());
                check(permanent.contract().orElseThrow().checkedClosure().stream().anyMatch(id -> id.linkage().startsWith("ironwood_bridge_destroy_")),
                        "generated destruction omitted from the checked closure");
                for (var type : List.of(IrType.reference("generatedproof.Holder"), IrType.reference("generatedproof.Holder$Item"),
                        IrType.reference("ironwood.lang.String"))) {
                    var reclaimed = BridgeNonReclamationAnalyzer.analyze(program, roots, type);
                    check(reclaimed.status() == BridgeProof.Status.REJECTED && reclaimed.reason().contains("deallocation"),
                            "generated root/String cleanup lost: " + type + ": " + reclaimed.reason());
                }
                check(BridgeRetentionAnalyzer.analyze(program, roots).values().stream().anyMatch(proof -> proof.status() != BridgeProof.Status.PROVED),
                        "non-reclamation classification granted retention permission");
                check(!artifact.bridgeConstructionFacts().orElseThrow().matches(program), "generated program reused source ownership facts");
            }
            var code = module.program().functions().stream().filter(function -> function.ownerClass().equals(enumType.referenceName())
                    && function.sourceName().equals("code")).findFirst().orElseThrow();
            var deallocation = inject(module.program(), code, new IrRawDeallocateInstruction(code.parameters().getFirst().value(), code.sourceSpan()));
            check(BridgeNonReclamationAnalyzer.analyze(deallocation, roots(deallocation), enumType).status() == BridgeProof.Status.REJECTED,
                    "reachable final enum deallocation admitted");
            var unknown = inject(module.program(), code, new IrSystemClockInstruction(new IrValueReference(1000000, IrType.I64, code.sourceSpan()),
                    IrSystemClockInstruction.Clock.NANO_TIME, code.sourceSpan()));
            var result = BridgeNonReclamationAnalyzer.analyze(unknown, roots(unknown), enumType);
            check(result.status() == BridgeProof.Status.UNKNOWN && result.reason().contains("IrSystemClockInstruction"),
                    "generated bridge operations hid unknown helper effects");
        }
    }

    private static BridgeRootSet roots(IrProgram program) {
        return BridgeRootSet.resolve(program, program.functions().stream().filter(function -> program.exportRoots().contains(function.linkageName()))
                .map(BridgeCallableId::of).toList());
    }

    private static IrProgram inject(IrProgram program, IrFunction target, IrInstruction effect) {
        var blocks = new ArrayList<>(target.blocks());
        var first = blocks.getFirst();
        var instructions = new ArrayList<>(first.instructions());
        instructions.add(effect);
        blocks.set(0, new IrBasicBlock(first.label(), instructions, first.terminator(), first.sourceSpan()));
        var replacement = new IrFunction(target.ownerClass(), target.sourceName(), target.linkageName(), target.returnType(),
                target.parameters(), blocks, target.sourceSpan(), target.sourceFileName(), target.kind());
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(), program.arrayTypes(),
                program.stringConstants(), program.dispatchSlots(), program.functions().stream().map(function -> function.equals(target) ? replacement : function).toList(),
                program.entryPoint(), program.allocationFailure(), program.exportRoots());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
