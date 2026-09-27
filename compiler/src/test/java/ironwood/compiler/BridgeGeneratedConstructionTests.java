// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeConstructionFacts;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class BridgeGeneratedConstructionTests {
    static final String NAME = "Java Bridge generated construction facts require exact synthesis and unpublished rollback";
    private static final String SOURCE = """
            package generatedconstruction;
            public final class Catalog {
                private final String label = new String("catalog");
                private static Catalog saved;
                public Catalog(boolean fail) { if (fail) throw new IllegalArgumentException(); }
                destructor { free label; }
                public String text() { saved = this; return label; }
                public int length(String input) { return input.length(); }
                public static Catalog current() { return saved; }
            }
            """;

    private BridgeGeneratedConstructionTests() {}

    static void proofs() throws Exception {
        var source = SourceFile.of("Catalog.iron", SOURCE);
        for (var mode : UnfreedMode.values()) {
            verify(analyze(List.of(source), mode));
            var artifact = analyze(List.of(source), mode);
            var facts = artifact.bridgeConstructionFacts().orElseThrow();
            var changed = analyze(List.of(SourceFile.of("Catalog.iron", SOURCE.replace("catalog", "changed"))), mode);
            denied(() -> facts.withGeneratedEntries(module(changed)));
            var roots = roots(artifact);
            var subset = BridgeRootSet.resolve(artifact.program().orElseThrow(), roots.roots().stream()
                    .filter(root -> !root.callable().name().equals("current")).map(BridgeRootSet.Root::callable).toList());
            var subsetModule = BridgeEntryModule.permanentObjects(artifact, subset);
            var projection = projection(artifact);
            var extraction = BridgeExceptionEntries.attach(artifact, subsetModule, projection);
            denied(() -> facts.withGeneratedEntries(module(artifact), extraction));
        }
        Path temporary = Files.createTempDirectory("bridge generated construction ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = temporary.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = temporary.resolve("construction.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, classes.resolve("generatedconstruction/Catalog.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("generatedconstruction"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                verify(analyze(loaded.sources(), UnfreedMode.OFF));
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void verify(CompilationArtifact artifact) {
        var module = module(artifact);
        var original = artifact.bridgeConstructionFacts().orElseThrow();
        var facts = original.withGeneratedEntries(module);
        check(facts.matches(module.program()) && !original.matches(module.program()), "source facts acquired implicit synthesis permission");
        check(facts.constructors().equals(original.constructors()) && facts.resultOrigins().equals(original.resultOrigins()),
                "synthesis changed original source facts");
        var type = IrType.reference("generatedconstruction.Catalog");
        check(query(module.program(), type, original).status() == BridgeProof.Status.REJECTED, "stale facts exempted generated rollback");
        var proof = query(module.program(), type, facts);
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        check(proof.contract().orElseThrow().unpublishedCleanups().stream().anyMatch(cleanup ->
                cleanup.caller().linkage().startsWith("ironwood_bridge_entry_")
                        && cleanup.kind() == BridgeUnpublishedCleanup.Kind.CONSTRUCTOR_UNWIND), "generated rollback was not explicitly attributed");
        for (var entry : module.entries()) {
            var id = BridgeCallableId.of(entry.function());
            check(!facts.borrowsInput(id, 0) && !facts.borrowsThroughResult(id, 0) && !facts.isStatic(id)
                    && !facts.isFinal(id) && !facts.isConstructibleConstructor(id) && !facts.resultOrigins().containsKey(id)
                    && !facts.constructors().containsKey(id), "generated method gained semantic facts");
        }
        denied(() -> facts.withGeneratedEntries(module));
        var extraction = BridgeExceptionEntries.attach(artifact, module, projection(artifact));
        var extended = original.withGeneratedEntries(module, extraction);
        check(extended.matches(extraction.program()) && !facts.matches(extraction.program()), "getters failed exact program binding");
        var complete = query(extraction.program(), type, extended);
        check(complete.status() == BridgeProof.Status.PROVED, complete.reason());
        check(complete.contract().orElseThrow().checkedClosure().contains(BridgeCallableId.of(extraction.trace())), "trace root omitted");
        check(query(module.program(), IrType.reference("ironwood.lang.String"), facts).status() == BridgeProof.Status.REJECTED,
                "temporary cleanup acquired permanent storage");
        var constructor = module.entries().stream().filter(entry -> entry.root().callable().kind() == IrCallableKind.CONSTRUCTOR)
                .findFirst().orElseThrow().function();
        var changed = inject(module.program(), constructor, new IrSystemClockInstruction(
                new IrValueReference(1000000, IrType.I64, constructor.sourceSpan()), IrSystemClockInstruction.Clock.NANO_TIME, constructor.sourceSpan()));
        check(!facts.matches(changed) && query(changed, type, facts).status() != BridgeProof.Status.PROVED,
                "changed generated body reused rollback proof");
        var sourceConstructor = artifact.program().orElseThrow().functions().stream().filter(function -> function.constructor()
                && function.ownerClass().equals(type.referenceName())).findFirst().orElseThrow();
        changed = inject(module.program(), sourceConstructor, new IrRawDeallocateInstruction(
                sourceConstructor.parameters().getFirst().value(), sourceConstructor.sourceSpan()));
        check(!facts.matches(changed) && query(changed, type, facts).status() == BridgeProof.Status.REJECTED,
                "changed source constructor reused no-escape proof");
        var finished = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(module.program()));
        check(!facts.matches(finished) && query(finished, type, facts).status() != BridgeProof.Status.PROVED,
                "optimized program silently reused synthesis facts");
    }

    private static BridgeExceptionProjection projection(CompilationArtifact artifact) {
        var proof = BridgeExceptionProjection.builtins(artifact, Set.of("ironwood.lang.IllegalArgumentException"));
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        return proof.contract().orElseThrow();
    }

    private static BridgeProof<BridgeNonReclamationContract> query(IrProgram program, IrType type, BridgeConstructionFacts facts) {
        var roots = BridgeRootSet.resolve(program, program.functions().stream().filter(function -> program.exportRoots().contains(function.linkageName()))
                .map(BridgeCallableId::of).toList());
        return BridgeNonReclamationAnalyzer.analyze(program, roots, type, facts);
    }

    private static CompilationArtifact analyze(List<SourceFile> sources, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static BridgeRootSet roots(CompilationArtifact artifact) {
        var selected = BridgeExportSurface.concreteObjects(artifact, List.of("generatedconstruction"));
        check(selected.surface().isPresent(), selected.diagnostics().toString());
        return selected.surface().orElseThrow().roots();
    }

    private static BridgeEntryModule module(CompilationArtifact artifact) {
        return BridgeEntryModule.permanentObjects(artifact, roots(artifact));
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

    private static void denied(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unmatched synthesis admitted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
