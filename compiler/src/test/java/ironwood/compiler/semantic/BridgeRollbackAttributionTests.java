// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.UnfreedMode;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BridgeRollbackAttributionTests {
    public static final String NAME = "Java Bridge rollback attributes protected allocations without allowing publication";
    private BridgeRollbackAttributionTests() {}

    public static void proofs() {
        for (var mode : UnfreedMode.values()) {
            var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Rollback.iron", """
                    final class Rollback {
                        private static IllegalStateException published;
                        static void wrapped(RuntimeException cause) { throw new IllegalStateException("wrapped", cause); }
                        static void cleanup(RuntimeException cause) {
                            try { throw cause; } finally { throw new IllegalStateException("cleanup", cause); }
                        }
                    }
                    """)));
            check(artifact.valid(), artifact.diagnostics().toString());
            var program = artifact.program().orElseThrow();
            var analysis = new BridgeRollbackAnalysis(program, artifact.bridgeConstructionFacts().orElseThrow());
            var published = program.staticFields().stream().filter(field -> field.name().equals("published")).findFirst().orElseThrow();
            int ordinary = 0, protectedAllocation = 0, conversions = 0;
            for (var function : program.functions()) {
                if (!List.of("wrapped", "cleanup").contains(function.sourceName())) continue;
                for (var block : function.blocks()) {
                    for (var operation : block.instructions()) {
                        if (!(operation instanceof IrRollbackInstruction rollback)) continue;
                        check(analysis.unwind(function, block, rollback).isPresent(), "valid rollback refused: " + block.label());
                        var predecessor = function.blocks().stream().filter(candidate -> candidate.terminator() instanceof IrInvokeTerminator invoke
                                && invoke.unwindTarget().equals(block.label())).findFirst().orElseThrow();
                        if (predecessor.instructions().stream().anyMatch(IrAllocateInstruction.class::isInstance)) ordinary++;
                        else protectedAllocation++;
                        if (predecessor.instructions().stream().anyMatch(IrReferenceConversionInstruction.class::isInstance)) conversions++;
                        var span = rollback.sourceSpan();
                        for (var inserted : List.<IrInstruction>of(
                                new IrCallInstruction(Optional.empty(), "unknown", IrType.VOID, List.of(), span),
                                new IrStaticFieldStoreInstruction(published, rollback.allocation(), span))) {
                            var instructions = new ArrayList<>(predecessor.instructions()); instructions.add(inserted);
                            var changed = replace(function, predecessor, new IrBasicBlock(predecessor.label(), instructions, predecessor.terminator(), predecessor.sourceSpan()));
                            check(analysis.unwind(changed, block, rollback).isEmpty(), "intervening effect granted rollback");
                        }
                        var blocks = new ArrayList<>(function.blocks());
                        blocks.add(new IrBasicBlock("alternate", List.of(), new IrJump(block.label(), span), span));
                        check(analysis.unwind(copy(function, blocks), block, rollback).isEmpty(), "alternate rollback incoming edge accepted");
                        if (predecessor.instructions().stream().noneMatch(IrAllocateInstruction.class::isInstance)) {
                            blocks = new ArrayList<>(function.blocks());
                            blocks.add(new IrBasicBlock("alternate", List.of(), new IrJump(predecessor.label(), span), span));
                            check(analysis.unwind(copy(function, blocks), block, rollback).isEmpty(), "alternate constructor incoming edge accepted");
                        }
                    }
                }
            }
            check(ordinary > 0 && protectedAllocation > 0 && conversions > 0, "rollback controls missed a lowering form");
        }
    }

    private static IrFunction replace(IrFunction function, IrBasicBlock before, IrBasicBlock after) {
        return copy(function, function.blocks().stream().map(block -> block.equals(before) ? after : block).toList());
    }

    private static IrFunction copy(IrFunction function, List<IrBasicBlock> blocks) {
        return new IrFunction(function.ownerClass(), function.sourceName(), function.linkageName(), function.returnType(),
                function.parameters(), blocks, function.sourceSpan(), function.sourceFileName(), function.kind());
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
