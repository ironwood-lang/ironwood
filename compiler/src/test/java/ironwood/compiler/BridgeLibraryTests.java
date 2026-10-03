// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeEntryModule;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Production final-link roots, including artifact reconstruction and cold entry guards. */
final class BridgeLibraryTests {
    static final String NAME = "Java Bridge library roots survive production optimization and artifact reconstruction";
    private static final String SOURCE = """
            package libraryfixture;
            public final class Engine {
                static int seed = initialize();
                static int initialize() { return 17; }
                public static int value(int input) { return seed + input; }
                public static long value(long input) { return seed + input; }
                public static int allocate(int input) {
                    Cell cell = new Cell(input);
                    int result = cell.read();
                    free cell;
                    return result;
                }
                public static int fail() { throw null; }
                public static int unused() { return 923; }
                public static void main(String[] arguments) { unused(); }
            }
            final class Cell {
                private int live;
                private int unused;
                Cell(int value) { live = value; unused = 91; }
                int read() { return live; }
            }
            """;

    private BridgeLibraryTests() {}

    static BridgeRootSet roots(CompilationArtifact artifact) {
        var program = artifact.program().orElseThrow();
        return BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().equals("libraryfixture.Engine")
                        && Set.of("value", "allocate", "fail").contains(function.sourceName()))
                .map(BridgeCallableId::of).toList());
    }

    static void rootsAndArtifacts() throws Exception {
        var pipeline = new CompilerPipeline(UnfreedMode.OFF);
        Path directory = Files.createTempDirectory("bridge library roots ");
        try {
            Path source = directory.resolve("Engine.iron");
            Files.writeString(source, SOURCE);
            var analyzed = pipeline.analyzeForBridge(List.of(SourceFile.read(source)));
            check(analyzed.valid(), analyzed.diagnostics().toString());
            var roots = roots(analyzed);
            var expected = verify(analyzed, roots);
            check(roots.roots().stream().filter(root -> root.callable().name().equals("value")).count() == 2,
                    "overloads collapsed");
            check(!pipeline.compileBridge(new CompilationArtifact(analyzed.program(), analyzed.llvmIr(),
                    analyzed.diagnostics()), roots).valid(), "unproved analysis admitted");
            var first = roots.roots().getFirst().callable();
            var stale = new BridgeCallableId(first.owner(), first.name(), first.linkage(), first.kind(),
                    first.parameters(), IrType.F64);
            check(!pipeline.compileBridge(analyzed, BridgeRootSet.resolve(analyzed.program().orElseThrow(),
                    List.of(stale))).valid(), "stale root admitted");
            check(!pipeline.compileBridge(analyzed, BridgeRootSet.resolve(analyzed.program().orElseThrow(),
                    List.of())).valid(), "empty surface admitted");
            var classes = directory.resolve("classes");
            var bytes = new java.io.ByteArrayOutputStream();
            var output = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, output, output) == 0, bytes.toString());
            var archive = directory.resolve("library.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, output, output) == 0,
                    bytes.toString());
            Files.delete(source);
            for (var container : List.of(classes, classes.resolve("libraryfixture/Engine.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("missing")), List.of(container))
                        .load(List.of(), List.of("libraryfixture.Engine"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var restored = pipeline.analyzeForBridge(loaded.sources());
                check(restored.valid(), restored.diagnostics().toString());
                check(roots.equals(roots.revalidate(restored.program().orElseThrow())), "artifact root identity changed");
                var actual = verify(restored, roots);
                var expectedFunctions = expected.program().orElseThrow().functions().stream()
                        .collect(Collectors.toMap(IrFunction::linkageName, function -> function));
                var actualFunctions = actual.program().orElseThrow().functions().stream()
                        .collect(Collectors.toMap(IrFunction::linkageName, function -> function));
                // Reconstructed declarations can have different ordering and type IDs.
                // Function identity, typed instructions and protected edges must match exactly.
                check(expectedFunctions.equals(actualFunctions), "production functions changed after artifact reconstruction: " + container);
                check(Set.copyOf(expected.program().orElseThrow().typeInitializations()).equals(
                        Set.copyOf(actual.program().orElseThrow().typeInitializations())), "initialization contracts changed");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static CompilationArtifact verify(CompilationArtifact analyzed, BridgeRootSet roots) {
        var module = BridgeEntryModule.scalars(analyzed, roots);
        var before = analyzed.program().orElseThrow();
        var pipeline = new CompilerPipeline(UnfreedMode.OFF);
        var compiled = pipeline.compileBridge(analyzed, roots);
        check(compiled.valid(), compiled.diagnostics().toString());
        var program = compiled.program().orElseThrow();
        check(program.entryPoint().isEmpty() && program.exportRoots().equals(module.entrySymbols()), "library roots lost");
        check(compiled.bridgeConstructionFacts().isEmpty(), "stale semantic facts attached after optimization");
        check(analyzed.program().orElseThrow().equals(before) && before.exportRoots().isEmpty(), "input artifact mutated");
        var symbols = program.functions().stream().map(IrFunction::linkageName).collect(Collectors.toSet());
        check(symbols.containsAll(program.exportRoots()) && program.exportRoots().size() == 4, "foreign-only entry pruned");
        check(program.functions().stream().noneMatch(function -> Set.of("unused", "main").contains(function.sourceName())),
                "unreachable source retained");
        check(program.allocationFailure().isPresent() && program.classes().stream().anyMatch(type ->
                type.name().equals(program.allocationFailure().orElseThrow().type().referenceName())), "OOM descriptor lost");
        for (var entry : module.entries()) {
            var retained = program.functions().stream().filter(function -> function.linkageName().equals(entry.function().linkageName()))
                    .findFirst().orElseThrow();
            check(BridgeCallableId.of(retained).equals(BridgeCallableId.of(entry.function())), "entry signature changed");
            check(operations(retained).anyMatch(IrEnsureTypeInitializedInstruction.class::isInstance), "foreign cold-init guard lost");
            check(retained.blocks().stream().anyMatch(block -> block.terminator() instanceof IrInvokeTerminator invoke
                    && invoke.unwindTarget().equals("failure")), "entry unwind edge lost");
        }
        check(program.functions().size() < module.program().functions().size(), "library pruning did not run");
        var staged = module.program();
        for (var transformed : List.of(InitializedTypeSpecializer.specialize(staged), EnumArgumentSpecializer.specialize(staged),
                FieldValueForwarder.forward(staged), ClosedWorldPruner.prune(staged), UnreadFieldStoreEliminator.eliminate(staged))) {
            check(transformed.exportRoots().equals(staged.exportRoots()), "transform discarded native roots");
        }
        return compiled;
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(block -> Stream.concat(block.instructions().stream(),
                block.terminator() instanceof IrInvokeTerminator invoke ? Stream.of(invoke.call()) : Stream.empty()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
