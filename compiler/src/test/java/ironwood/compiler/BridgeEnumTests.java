// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class BridgeEnumTests {
    private BridgeEnumTests() {}

    static final String SOURCE = """
            package enumfixture;
            enum Side {
                BUY(0, 11), SELL(1, 29);
                private final int position;
                private final int code;
                Side(int position, int code) { this.position = position; this.code = code; }
                public final int index() { return position; }
                public final int code() { return code; }
            }
            final class Operations {
                static int select(Side side) { return side == null ? -1 : side.code(); }
            }
            """;
    static final IrType SIDE = IrType.reference("enumfixture.Side");
    static final Map<IrType, Map<String, Integer>> TOKENS = Map.of(SIDE, Map.of("BUY", 41, "SELL", 7));

    static CompilationArtifact artifact(String source) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(
                List.of(SourceFile.of("test/BridgeEnums.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    static BridgeRootSet roots(CompilationArtifact artifact, String... names) {
        var program = artifact.program().orElseThrow();
        var selected = Set.of(names);
        return BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().startsWith("enumfixture.") && selected.contains(function.sourceName()))
                .map(BridgeCallableId::of).toList());
    }

    static void proofs() throws Exception {
        var artifact = artifact(SOURCE);
        var roots = roots(artifact, "index", "select");
        var proof = BridgeEnumInputs.prove(artifact, roots, TOKENS);
        check(proof.matches(artifact.program().orElseThrow(), roots), "proof is not bound");
        check(!proof.matches(artifact.program().orElseThrow(), roots(artifact, "select")), "stale export closure admitted");
        check(proof.constants().get(SIDE).stream().anyMatch(value -> value.token() == 7
                && value.field().name().equals("SELL")), "token is not paired by name");
        refused(() -> BridgeEnumInputs.prove(artifact, roots, Map.of()), "missing mapping");
        refused(() -> BridgeEnumInputs.prove(artifact, roots, Map.of(SIDE, Map.of("BUY", 41))), "missing constant");
        refused(() -> BridgeEnumInputs.prove(artifact, roots, Map.of(SIDE, Map.of("BUY", 7, "SELL", 7))), "duplicate token");
        refused(() -> BridgeEnumInputs.prove(artifact, roots, Map.of(SIDE, Map.of("BUY", -1, "SELL", 7))), "null token");
        var virtual = artifact(SOURCE.replace("public final int index()", "public int index()"));
        refused(() -> BridgeEnumInputs.prove(virtual, roots(virtual, "index"), TOKENS), "unproved final dispatch");
        var retaining = artifact(SOURCE.replace("static int select(Side side) {", "static Side saved; static int select(Side side) { saved = side;"));
        refused(() -> BridgeEnumInputs.prove(retaining, roots(retaining, "select"), TOKENS), "static input retention");
        var initialization = artifact(SOURCE.replace("private final int position;", "static Side saved = BUY; private final int position;"));
        refused(() -> BridgeEnumInputs.prove(initialization, roots(initialization, "select"), TOKENS),
                "conversion initialization must be included even without enum method roots");
        var unknown = artifact(SOURCE.replace("return side == null", "System.out.println(1); return side == null"));
        refused(() -> BridgeEnumInputs.prove(unknown, roots(unknown, "select"), TOKENS), "unknown effects");
        var retention = BridgeRetentionAnalyzer.analyze(artifact.program().orElseThrow(), roots);
        check(retention.values().stream().allMatch(value -> value.status() == BridgeProof.Status.PROVED),
                "exact enum singleton publication was not recognized");
        var module = BridgeEntryModule.enums(artifact, roots, TOKENS);
        for (var entry : module.entries()) {
            var loads = entry.function().blocks().stream().flatMap(block -> block.instructions().stream())
                    .filter(IrStaticFieldLoadInstruction.class::isInstance).map(IrStaticFieldLoadInstruction.class::cast).toList();
            check(loads.size() == 2 && loads.stream().allMatch(load -> load.field().ownerClass().equals("enumfixture.Side")),
                    "conversion must load named public singleton fields");
            for (var block : entry.function().blocks()) {
                if (block.terminator() instanceof IrInvokeTerminator invoke) check(invoke.unwindTarget().equals(
                        invoke.call() instanceof IrBridgeFailureSnapshotInstruction ? "snapshot.failure" : "failure"),
                        "unprotected enum initialization or target");
            }
        }
        new ironwood.compiler.backend.LlvmEmitter().emit(module);
        artifacts();
    }

    private static void artifacts() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("bridge enum artifacts ");
        try {
            var source = directory.resolve("Enums.iron");
            java.nio.file.Files.writeString(source, SOURCE);
            var original = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.read(source)));
            var originalRoots = roots(original, "index", "select");
            var expected = BridgeEntryModule.enums(original, originalRoots, TOKENS).entries();
            var expectedConstants = BridgeEnumInputs.prove(original, originalRoots, TOKENS).constants();
            var classes = directory.resolve("classes");
            var output = new java.io.ByteArrayOutputStream();
            var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, stream, stream) == 0, output.toString());
            var archive = directory.resolve("enums.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) == 0, output.toString());
            java.nio.file.Files.delete(source);
            for (var container : List.of(classes, classes.resolve("enumfixture/Side.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("missing")), List.of(container))
                        .load(List.of(), List.of("enumfixture.Side", "enumfixture.Operations"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var reconstructed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(reconstructed.valid(), reconstructed.diagnostics().toString());
                var reconstructedRoots = roots(reconstructed, "index", "select");
                var actual = BridgeEntryModule.enums(reconstructed, reconstructedRoots, TOKENS).entries();
                // Unrelated global type/dispatch numbering follows dependency discovery order.
                // Compare the resolved entry CFG and named conversion contract, not those IDs.
                check(expected.equals(actual) && expectedConstants.equals(
                        BridgeEnumInputs.prove(reconstructed, reconstructedRoots, TOKENS).constants()),
                        "enum proof/lowering changed after reconstruction: " + container);
            }
        } finally {
            try (var paths = java.nio.file.Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
            }
        }
    }

    private static void refused(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
