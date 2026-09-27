// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BridgeSecondaryRetentionTests {
    private BridgeSecondaryRetentionTests() {}

    private static final Set<String> SAFE = Set.of("fresh", "helper", "recursive", "caught", "nullThrow", "initializer");
    private static final Set<String> UNSAFE = Set.of("primaryInput", "secondaryInput", "mixed", "loaded", "caughtInput", "unknown", "hidden");
    private static final String SOURCE = """
            package secondary;
            class Holder { RuntimeException failure; }
            class Initialization {
                static int value = fail();
                static int fail() { try { throw new IllegalArgumentException("primary"); } finally { throw new IllegalStateException("secondary"); } }
            }
            class Effects {
                static void throwFresh() { throw new IllegalArgumentException("primary"); }
                static RuntimeException capture(RuntimeException input) { try { throw input; } catch (RuntimeException caught) { return caught; } }
                static int fresh() { try { throw new IllegalArgumentException("primary"); } finally { throw new IllegalStateException("secondary"); } }
                static int helper() { try { throwFresh(); } finally { throw new IllegalStateException("secondary"); } }
                static int recursive(int depth) {
                    try { if (depth > 0) return recursive(depth - 1); throw new IllegalArgumentException("primary"); }
                    finally { throw new IllegalStateException("secondary"); }
                }
                static int caught() {
                    try { throwFresh(); } catch (RuntimeException caught) {
                        try { throw caught; } finally { throw new IllegalStateException("secondary"); }
                    }
                    return 0;
                }
                static int nullThrow() { try { throw null; } finally { throw new IllegalStateException("secondary"); } }
                static int initializer() { return Initialization.value; }
                static int primaryInput(RuntimeException input) { try { throw input; } finally { throw new IllegalStateException("secondary"); } }
                static int secondaryInput(RuntimeException input) { try { throwFresh(); } finally { throw input; } }
                static int mixed(RuntimeException input, boolean choose) {
                    RuntimeException primary = choose ? input : new IllegalArgumentException("fresh");
                    try { throw primary; } finally { throw new IllegalStateException("secondary"); }
                }
                static int loaded(Holder holder) { try { throw holder.failure; } finally { throw new IllegalStateException("secondary"); } }
                static int caughtInput(RuntimeException input) { try { throw capture(input); } finally { throw new IllegalStateException("secondary"); } }
                static int unknown(String input) {
                    try { throw new IllegalArgumentException(input.repeat(2)); } finally { throw new IllegalStateException("secondary"); }
                }
                static int hidden(RuntimeException input) {
                    try { throw new IllegalArgumentException("primary", input); } finally { throw new IllegalStateException("secondary"); }
                }
            }
            """;

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            verify(List.of(SourceFile.of("Effects.iron", SOURCE)), mode);
            var unsafe = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Unsafe.iron", """
                    class Unsafe {
                        static String fail() {
                            RuntimeException value = new IllegalArgumentException("kept");
                            try { throw value; } catch (RuntimeException caught) { free value; return caught.getMessage(); }
                        }
                    }
                    """)));
            check(!unsafe.valid() && unsafe.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("cannot free 'value'")),
                    "caught provenance weakened free proof: " + unsafe.diagnostics());
        }
        var analyzed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Effects.iron", SOURCE)));
        var original = analyzed.program().orElseThrow();
        var missing = new IrProgram(original.moduleName(), original.classes(), original.staticFields(), original.typeInitializations(),
                original.arrayTypes(), original.stringConstants(), original.dispatchSlots(), original.functions().stream()
                .filter(function -> !function.linkageName().equals("ironwood.secondary.Effects.throwFresh")).toList(), original.entryPoint(),
                original.allocationFailure(), original.exportRoots());
        var helper = roots(missing, Set.of("helper"));
        check(BridgeRetentionAnalyzer.analyze(missing, helper).values().stream().allMatch(proof -> proof.status() != BridgeProof.Status.PROVED),
                "missing throwing helper acquired independent origin");
        var fresh = original.functions().stream().filter(function -> function.linkageName().equals("ironwood.secondary.Effects.fresh")).findFirst().orElseThrow();
        var handle = fresh.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(IrExceptionLandingPadInstruction.class::isInstance).map(IrExceptionLandingPadInstruction.class::cast)
                .findFirst().orElseThrow().exceptionHandle();
        for (var opaque : List.of(handle, new IrValueReference(9000, IrType.EXCEPTION, fresh.sourceSpan()))) {
            var blocks = fresh.blocks().stream().map(block -> new IrBasicBlock(block.label(), block.instructions().stream()
                    .map(instruction -> instruction instanceof IrAddSecondaryExceptionInstruction association
                            ? (IrInstruction) new IrAddSecondaryExceptionInstruction(association.primary(), opaque, association.sourceSpan()) : instruction)
                    .toList(), block.terminator(), block.sourceSpan())).toList();
            var altered = new IrFunction(fresh.ownerClass(), fresh.sourceName(), fresh.linkageName(), fresh.returnType(), fresh.parameters(),
                    blocks, fresh.sourceSpan(), fresh.sourceFileName(), fresh.kind());
            var changed = new IrProgram(original.moduleName(), original.classes(), original.staticFields(), original.typeInitializations(),
                    original.arrayTypes(), original.stringConstants(), original.dispatchSlots(), original.functions().stream()
                    .map(function -> function.equals(fresh) ? altered : function).toList(), original.entryPoint(),
                    original.allocationFailure(), original.exportRoots());
            check(BridgeRetentionAnalyzer.analyze(changed, roots(changed, Set.of("fresh"))).values().stream()
                    .allMatch(proof -> proof.status() != BridgeProof.Status.PROVED), "opaque/missing exception value acquired an origin");
        }
        Path directory = Files.createTempDirectory("bridge-secondary-retention-");
        try {
            Path source = directory.resolve("Effects.iron"); Files.writeString(source, SOURCE);
            var expected = verify(List.of(SourceFile.read(source)), UnfreedMode.OFF);
            var output = new ByteArrayOutputStream(); var print = new PrintStream(output, true, StandardCharsets.UTF_8);
            Path classes = directory.resolve("classes"), archive = directory.resolve("secondary.ironjar");
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, print, print) == 0, output.toString());
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, print, print) == 0, output.toString());
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("secondary/Effects.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(container)).load(List.of(), List.of("secondary.Effects"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                for (var mode : UnfreedMode.values()) check(expected.equals(verify(loaded.sources(), mode)), "secondary origin artifact parity: " + container);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Map<BridgeCallableId, BridgeProof<BridgeRetentionContract>> verify(List<SourceFile> sources, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        var program = artifact.program().orElseThrow();
        var names = new java.util.LinkedHashSet<>(SAFE); names.addAll(UNSAFE);
        var selected = roots(program, names);
        var proofs = BridgeRetentionAnalyzer.analyze(program, selected, artifact.bridgeConstructionFacts().orElseThrow());
        check(proofs.size() == names.size(), "missing secondary proof");
        for (var entry : proofs.entrySet()) {
            check((entry.getValue().status() == BridgeProof.Status.PROVED) == SAFE.contains(entry.getKey().name()), entry.toString());
            if (entry.getValue().status() == BridgeProof.Status.PROVED) check(entry.getValue().contract().orElseThrow().slots().isEmpty(), "independent association retained an input");
        }
        check(BridgeEntryModule.scalars(artifact, roots(program, SAFE)).entries().size() == SAFE.size(), "safe secondary entry missing");
        return proofs;
    }

    private static BridgeRootSet roots(IrProgram program, Set<String> names) {
        return BridgeRootSet.resolve(program, program.functions().stream().filter(function -> function.ownerClass().equals("secondary.Effects")
                && names.contains(function.sourceName())).map(BridgeCallableId::of).toList());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
