// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class BridgeStringResultTests {
    private BridgeStringResultTests() {}

    private static final String SOURCE = """
            package results;
            final class Values {
                static String literal(boolean empty) { return empty ? null : helper(); }
                static String helper() { return "copied"; }
                static String nil() { return null; }
                static String alias(String first, String second, boolean choose) { return choose ? first : second; }
                static String fresh(String input, boolean empty) { return empty ? null : new String(input); }
                static String identity(String input) { return input; }
                static String loop(String input, int count) {
                    String value = input;
                    while (count-- > 0) value = identity(value);
                    return value;
                }
            }
            """;

    private static BridgeRootSet roots(CompilationArtifact artifact) {
        var program = artifact.program().orElseThrow();
        return BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().equals("results.Values") && function.returnType().isReference())
                .map(BridgeCallableId::of).toList());
    }

    private static Map<BridgeCallableId, BridgeProof<BridgeStringResultContract>> prove(List<SourceFile> sources,
            UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        return BridgeStringResults.prove(artifact, roots(artifact));
    }

    static void proofs() throws Exception {
        for (var mode : UnfreedMode.values()) {
            var sources = List.of(SourceFile.of("Values.iron", SOURCE));
            var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
            check(artifact.valid(), artifact.diagnostics().toString());
            var proofs = BridgeStringResults.prove(artifact, roots(artifact));
            check(proofs.size() == 7, "missing result proof");
            for (var entry : proofs.entrySet()) {
                check(entry.getValue().status() == BridgeProof.Status.PROVED, entry.toString());
                var contract = entry.getValue().contract().orElseThrow();
                var expected = switch (entry.getKey().name()) {
                    case "fresh" -> BridgeStringResultContract.Kind.FRESH;
                    case "alias", "identity", "loop" -> BridgeStringResultContract.Kind.INPUT_ALIAS;
                    default -> BridgeStringResultContract.Kind.IMMORTAL;
                };
                check(contract.kind() == expected, contract.toString());
                if (expected == BridgeStringResultContract.Kind.INPUT_ALIAS) {
                    check(contract.inputs().equals(entry.getKey().name().equals("alias")
                            ? java.util.Set.of(0, 1) : java.util.Set.of(0)), "alias alternatives lost");
                    var facts = artifact.bridgeConstructionFacts().orElseThrow();
                    check(!facts.borrowsInput(entry.getKey(), 0) && facts.borrowsThroughResult(entry.getKey(), 0),
                            "return-only borrowing weakened ordinary borrowing");
                }
            }
            for (String body : List.of("saved = input; return input;", "free input; return null;",
                    "return choose ? new String(input) : input;", "return saved;",
                    "return choose ? null : input.repeat(2);",
                    "System.out.println(input); return input;",
                    "String result = new String(input); saved = result; return result;",
                    "throw new RuntimeException(input);")) {
                var bad = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron",
                        "package results; final class Values { static String saved; static String bad(String input, "
                                + "boolean choose) { " + body + " } }")));
                if (!bad.valid()) continue; // Ordinary mandatory rejection takes precedence.
                if (body.contains("input.repeat")) {
                    check(ironwood.compiler.semantic.BridgeRetentionAnalyzer.immortalStringResults(
                            bad.program().orElseThrow(), roots(bad)).isEmpty(), "unknown phi alternative vanished");
                }
                var proof = BridgeStringResults.prove(bad, roots(bad));
                check(proof.size() == 1 && proof.values().iterator().next().status() != BridgeProof.Status.PROVED,
                        "unsafe String result admitted: " + body + " " + proof);
            }
            var changed = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Values.iron",
                    SOURCE.replace("\"copied\"", "\"changed\""))));
            var stale = new CompilationArtifact(changed.program(), changed.llvmIr(), changed.diagnostics(),
                    artifact.bridgeConstructionFacts(), changed.bridgeApiFacts());
            try {
                BridgeStringResults.prove(stale, roots(stale));
                throw new AssertionError("stale String result facts admitted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("do not match"), expected.toString());
            }
        }
        artifacts(SOURCE, true);
        artifacts("package results; final class Values { static String saved; "
                + "static String bad(String input) { saved = input; return input; } }", false);
    }

    private static void artifacts(String sourceText, boolean accepted) throws Exception {
        Path directory = Files.createTempDirectory("bridge-string-results-");
        try {
            Path source = directory.resolve("Values.iron");
            Files.writeString(source, sourceText);
            var expected = prove(List.of(SourceFile.read(source)), UnfreedMode.OFF);
            check(!expected.isEmpty() && expected.values().stream().allMatch(proof ->
                    (proof.status() == BridgeProof.Status.PROVED) == accepted), "wrong source proof expectation");
            Path classes = directory.resolve("classes");
            var bytes = new ByteArrayOutputStream();
            var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, output, output) == 0, bytes.toString());
            Path archive = directory.resolve("results.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, output, output) == 0,
                    bytes.toString());
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("results/Values.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(container))
                        .load(List.of(), List.of("results.Values"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                check(expected.equals(prove(loaded.sources(), UnfreedMode.OFF)), "String result proof changed: " + container);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
