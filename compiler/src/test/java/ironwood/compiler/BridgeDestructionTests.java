// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeDestructionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Set;

final class BridgeDestructionTests {
    private BridgeDestructionTests() {}

    private static final Set<String> TYPES = Set.of("rootfixture.Item", "rootfixture.Holder");
    private static final Set<String> METHODS = Set.of("set", "clear", "setThenFail", "other");
    private static final IrType HOLDER = IrType.reference("rootfixture.Holder");
    private static final String SOURCE = BridgeRootRetentionTests.SOURCE.replace("Holder(Item item) { first = item; }", """
            private int[] storage;
            static int destroyed;
            Holder(Item item) { first = item; storage = new int[2]; }
            destructor { free storage; first = null; destroyed++; }
            """);

    static void proofs() throws Exception {
        var artifact = BridgeRootRetentionTests.artifact(SOURCE, UnfreedMode.OFF);
        var roots = BridgeRootRetentionTests.roots(artifact, METHODS, TYPES);
        var proof = BridgeDestructionAnalyzer.analyze(artifact, roots, HOLDER);
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        check(!proof.contract().orElseThrow().checkedCleanup().isEmpty(), "destructor closure was skipped");
        var empty = BridgeDestructionAnalyzer.analyze(artifact, roots, IrType.reference("rootfixture.Item"));
        check(empty.status() == BridgeProof.Status.PROVED && empty.contract().orElseThrow().checkedCleanup().isEmpty(),
                "descriptor without a destructor was not proved");
        check(BridgeDestructionAnalyzer.analyze(artifact, roots, IrType.reference("ironwood.lang.Object")).status()
                == BridgeProof.Status.REJECTED, "unowned type gained destruction");
        var unknown = BridgeRootRetentionTests.artifact(SOURCE.replace("destroyed++;", "long ignored = System.nanoTime();"), UnfreedMode.OFF);
        var denied = BridgeDestructionAnalyzer.analyze(unknown, BridgeRootRetentionTests.roots(unknown, METHODS, TYPES), HOLDER);
        check(denied.status() == BridgeProof.Status.UNKNOWN && denied.reason().contains("unclassified"), denied.toString());
        var retaining = BridgeRootRetentionTests.artifact(SOURCE.replace("static int destroyed;", "static int destroyed; static String saved;")
                .replace("destroyed++;", "saved = \"retained\";"), UnfreedMode.OFF);
        denied = BridgeDestructionAnalyzer.analyze(retaining, BridgeRootRetentionTests.roots(retaining, METHODS, TYPES), HOLDER);
        check(denied.status() == BridgeProof.Status.REJECTED, "cleanup hidden retention admitted: " + denied);
        for (var mode : UnfreedMode.values()) {
            for (String operation : List.of("throw null;", "int[] temporary = new int[1]; free temporary;")) {
                var sources = List.of(SourceFile.of("test/BadDestruction.iron", SOURCE.replace("destroyed++;", operation)));
                var ordinary = new CompilerPipeline(mode).analyze(sources);
                var bridge = new CompilerPipeline(mode).analyzeForBridge(sources);
                check(!ordinary.valid() && ordinary.diagnostics().equals(bridge.diagnostics()),
                        "ordinary destructor rejection changed: " + operation);
                check(BridgeDestructionAnalyzer.analyze(bridge, roots, HOLDER).status() != BridgeProof.Status.PROVED,
                        "invalid source gained destruction");
            }
        }
        artifacts();
    }

    private static void artifacts() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("bridge destruction artifacts ");
        try {
            var source = directory.resolve("Roots.iron");
            java.nio.file.Files.writeString(source, SOURCE);
            var original = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.read(source)));
            var expected = BridgeDestructionAnalyzer.analyze(original,
                    BridgeRootRetentionTests.roots(original, METHODS, TYPES), HOLDER).contract().orElseThrow();
            var classes = directory.resolve("classes");
            var output = new java.io.ByteArrayOutputStream();
            var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, stream, stream) == 0, output.toString());
            var archive = directory.resolve("destruction.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) == 0, output.toString());
            java.nio.file.Files.delete(source);
            for (var container : List.of(classes, classes.resolve("rootfixture/Holder.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("missing")), List.of(container))
                        .load(List.of(), List.of("rootfixture.Holder", "rootfixture.Item"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var reconstructed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                var proof = BridgeDestructionAnalyzer.analyze(reconstructed,
                        BridgeRootRetentionTests.roots(reconstructed, METHODS, TYPES), HOLDER);
                check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
                check(expected.checkedCleanup().equals(proof.contract().orElseThrow().checkedCleanup())
                        && expected.ownership().entries().equals(proof.contract().orElseThrow().ownership().entries()),
                        "destruction contract changed after reconstruction: " + container);
            }
        } finally {
            try (var paths = java.nio.file.Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
            }
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
