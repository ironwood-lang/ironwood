// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeNonReclamationContract;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.bridge.BridgeUnpublishedCleanup;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BridgeOrderBookTests {
    private BridgeOrderBookTests() {}

    static void construction() {
        verify(sourceArtifact());
    }

    private static CompilationArtifact sourceArtifact() {
        Path root = Path.of("projects/OrderBook/src/main/ironwood");
        var loaded = new SourceSetLoader(List.of(root), List.of())
                .load(List.of(root.resolve("org/ironwood/orderbook/OrderBook.iron")));
        check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
        return new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
    }

    private static Map<IrType, BridgeProof<BridgeNonReclamationContract>> verify(CompilationArtifact artifact) {
        check(artifact.valid(), artifact.diagnostics().toString());
        var program = artifact.program().orElseThrow();
        var names = Set.of("OrderBook", "createLimit", "cancel", "reduceTo");
        var ids = program.functions().stream().filter(function -> function.ownerClass().startsWith("org.ironwood.orderbook.")
                        && names.contains(function.sourceName())).map(BridgeCallableId::of).toList();
        var roots = BridgeRootSet.resolve(program, ids);
        check(roots.resolved(), roots.problems().toString());
        var book = IrType.reference("org.ironwood.orderbook.OrderBook");
        var order = IrType.reference("org.ironwood.orderbook.Order");
        var level = IrType.reference("org.ironwood.orderbook.PriceLevel");
        Map<IrType, BridgeProof<BridgeNonReclamationContract>> proofs = new LinkedHashMap<>();
        for (var type : List.of(book, order, level, IrType.array(order), IrType.array(level), IrType.array(IrType.I32))) {
            var proof = BridgeNonReclamationAnalyzer.analyze(program, roots, type,
                    artifact.bridgeConstructionFacts().orElseThrow());
            check(proof.status() == BridgeProof.Status.PROVED, type.displayName() + ": " + proof.reason());
            proofs.put(type, proof);
            var cleanups = proof.contract().orElseThrow().unpublishedCleanups();
            check(cleanups.stream().anyMatch(cleanup -> cleanup.kind() == BridgeUnpublishedCleanup.Kind.ENTRY_FAILURE
                    && cleanup.construction().constructor().owner().equals(book.referenceName())), "missing book failure proof");
            check(cleanups.stream().anyMatch(cleanup -> cleanup.kind() == BridgeUnpublishedCleanup.Kind.CONSTRUCTOR_UNWIND
                    && cleanup.construction().constructor().owner().equals(order.referenceName())), "missing pooled Order unwind proof");
            check(cleanups.stream().anyMatch(cleanup -> cleanup.kind() == BridgeUnpublishedCleanup.Kind.CONSTRUCTOR_UNWIND
                    && cleanup.construction().constructor().owner().equals(level.referenceName())), "missing pooled level unwind proof");
            var bookCleanup = cleanups.stream().filter(cleanup -> cleanup.kind() == BridgeUnpublishedCleanup.Kind.ENTRY_FAILURE)
                    .findFirst().orElseThrow().construction();
            check(bookCleanup.ownedStorageFields().stream().map(field -> field.name()).toList().equals(List.of("tail")),
                    "actual OrderBook rollback field set changed; recheck allocation-failure evidence");
            check(bookCleanup.ownedElementFields().isEmpty(), "pooled-element destruction must not be invented");
        }
        check(BridgeNonReclamationAnalyzer.analyze(program, roots, book).status() == BridgeProof.Status.REJECTED,
                "actual construction accepted without semantic facts");
        var changed = new IrProgram(program.moduleName() + ".changed", program.classes(), program.staticFields(),
                program.typeInitializations(), program.arrayTypes(), program.stringConstants(), program.dispatchSlots(),
                program.functions(), program.entryPoint(), program.allocationFailure());
        check(BridgeNonReclamationAnalyzer.analyze(changed, roots, book, artifact.bridgeConstructionFacts().orElseThrow())
                .status() == BridgeProof.Status.REJECTED, "stale construction facts authorized cleanup");
        return Map.copyOf(proofs);
    }

    static void artifacts() throws Exception {
        Path temporary = Files.createTempDirectory("bridge OrderBook artifacts ");
        try {
            var expected = verify(sourceArtifact());
            Path root = Path.of("projects/OrderBook/src/main/ironwood");
            Path classes = temporary.resolve("classes");
            var output = new ByteArrayOutputStream();
            var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{root.resolve("org/ironwood/orderbook/OrderBook.iron").toString(),
                    "--source-path", root.toString(), "-d", classes.toString()}, stream, stream) == 0,
                    output.toString(StandardCharsets.UTF_8));
            Path archive = temporary.resolve("orderbook.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) == 0,
                    output.toString(StandardCharsets.UTF_8));
            for (Path container : List.of(classes, classes.resolve("org/ironwood/orderbook/OrderBook.ironclass"), archive)) {
                // Individual source containers do not embed other source files.
                var classPath = container.toString().endsWith(".ironclass")
                        ? List.of(container, classes.resolve("org/ironwood/orderbook/Order.ironclass"),
                                classes.resolve("org/ironwood/orderbook/PriceLevel.ironclass")) : List.of(container);
                var loaded = new SourceSetLoader(List.of(temporary.resolve("missing")), classPath)
                        .load(List.of(), List.of("org.ironwood.orderbook.OrderBook"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), container + ": " + artifact.diagnostics());
                check(expected.equals(verify(artifact)), "OrderBook proof differs after reconstruction: " + container);
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
